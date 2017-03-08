/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.block;

import alluxio.exception.BlockAlreadyExistsException;
import alluxio.exception.BlockDoesNotExistException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.UfsBlockAccessTokenUnavailableException;
import alluxio.worker.block.io.BlockReader;
import alluxio.worker.block.io.BlockWriter;
import alluxio.worker.block.meta.UnderFileSystemBlockMeta;
import alluxio.worker.block.options.OpenUfsBlockOptions;

import com.google.common.base.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.concurrent.GuardedBy;

/**
 * This class manages the virtual blocks in the UFS for delegated UFS reads/writes.
 *
 * The usage pattern:
 *  acquireAccess(blockMeta, maxConcurrency)
 *  cleanup(sessionId, blockId)
 *  releaseAccess(sessionId, blockId)
 *
 * If the client is lost before releasing or cleaning up the session, the session cleaner will
 * clean the data.
 */
public final class UnderFileSystemBlockStore {
  private static final Logger LOG = LoggerFactory.getLogger(UnderFileSystemBlockStore.class);

  /**
   * This lock protects mBlocks, mSessionIdToBlockIds and mBlockIdToSessionIds. For any read/write
   * operations to these maps, the lock needs to be acquired. But once you get the block
   * information from the map (e.g. mBlocks), the lock does not need to be acquired. For example,
   * the block reader/writer within the BlockInfo can be updated without acquiring this lock.
   * This is based on the assumption that one session won't open multiple readers/writers on the
   * same block. If the client do that, the client can see failures but the worker won't crash.
   */
  private final ReentrantLock mLock = new ReentrantLock();
  @GuardedBy("mLock")
  /** Maps from the {@link Key} to the {@link BlockInfo}. */
  private final Map<Key, BlockInfo> mBlocks = new HashMap<>();
  @GuardedBy("mLock")
  /** Maps from the session ID to the block IDs. */
  private final Map<Long, Set<Long>> mSessionIdToBlockIds = new HashMap<>();
  @GuardedBy("mLock")
  /** Maps from the block ID to the session IDs. */
  private final Map<Long, Set<Long>> mBlockIdToSessionIds = new HashMap<>();

  /** The Alluxio block store. */
  private final BlockStore mAlluxioBlockStore;

  /**
   * Creates an instance of {@link UnderFileSystemBlockStore}.
   *
   * @param alluxioBlockStore the Alluxio block store
   */
  public UnderFileSystemBlockStore(BlockStore alluxioBlockStore) {
    mAlluxioBlockStore = alluxioBlockStore;
  }

  /**
   * Acquires access for a UFS block given a {@link UnderFileSystemBlockMeta} and the limit on
   * the maximum concurrency on the block.
   *
   * @param sessionId the session ID
   * @param blockId maximum concurrency
   * @param options the options
   * @throws BlockAlreadyExistsException if the block already exists for a session ID
   * @throws UfsBlockAccessTokenUnavailableException if there are too many concurrent sessions
   *         accessing the block
   */
  // TODO(peis): Avoid throwing UfsBlockAccessTokenUnavailableException by returning a status.
  public void acquireAccess(long sessionId, long blockId, OpenUfsBlockOptions options)
      throws BlockAlreadyExistsException, UfsBlockAccessTokenUnavailableException {
    UnderFileSystemBlockMeta blockMeta = new UnderFileSystemBlockMeta(sessionId, blockId, options);
    mLock.lock();
    try {
      Key key = new Key(sessionId, blockId);
      if (mBlocks.containsKey(key)) {
        throw new BlockAlreadyExistsException(ExceptionMessage.UFS_BLOCK_ALREADY_EXISTS_FOR_SESSION,
            blockId, blockMeta.getUnderFileSystemPath(), sessionId);
      }
      Set<Long> sessionIds = mBlockIdToSessionIds.get(blockId);
      if (sessionIds != null && sessionIds.size() >= options.getMaxUfsReadConcurrency()) {
        throw new UfsBlockAccessTokenUnavailableException(
            ExceptionMessage.UFS_BLOCK_ACCESS_TOKEN_UNAVAILABLE, sessionIds.size(), blockId,
            blockMeta.getUnderFileSystemPath());
      }
      if (sessionIds == null) {
        sessionIds = new HashSet<>();
        mBlockIdToSessionIds.put(blockId, sessionIds);
      }
      sessionIds.add(sessionId);

      mBlocks.put(key, new BlockInfo(blockMeta));

      Set<Long> blockIds = mSessionIdToBlockIds.get(sessionId);
      if (blockIds == null) {
        blockIds = new HashSet<>();
        mSessionIdToBlockIds.put(sessionId, blockIds);
      }
      blockIds.add(blockId);
    } finally {
      mLock.unlock();
    }
  }

  /**
   * Cleans up the block reader or writer and checks whether it is necessary to commit the block
   * to Alluxio block store.
   *
   * During UFS block read, this is triggered when the block is unlocked.
   * During UFS block write, this is triggered when the UFS block is committed.
   *
   * @param sessionId the session ID
   * @param blockId the block ID
   * @return true if block is to be committed into Alluxio block store
   * @throws IOException if it fails to clean up
   */
  public boolean cleanup(long sessionId, long blockId) throws IOException {
    BlockInfo blockInfo;
    mLock.lock();
    try {
      blockInfo = mBlocks.get(new Key(sessionId, blockId));
      if (blockInfo == null) {
        return false;
      }
    } finally {
      mLock.unlock();
    }
    blockInfo.closeReaderOrWriter();
    return blockInfo.getMeta().getCommitPending();
  }

  /**
   * Releases the access token of this block by removing this (sessionId, blockId) pair from the
   * store.
   *
   * @param sessionId the session ID
   * @param blockId the block ID
   */
  public void releaseAccess(long sessionId, long blockId) {
    mLock.lock();
    try {
      Key key = new Key(sessionId, blockId);
      mBlocks.remove(key);
      Set<Long> blockIds = mSessionIdToBlockIds.get(sessionId);
      if (blockIds != null) {
        blockIds.remove(blockId);
      }
      Set<Long> sessionIds = mBlockIdToSessionIds.get(blockId);
      if (sessionIds != null) {
        sessionIds.remove(sessionId);
      }
    } finally {
      mLock.unlock();
    }
  }

  /**
   * Cleans up all the block information(e.g. block reader/writer) that belongs to this session.
   *
   * @param sessionId the session ID
   */
  public void cleanupSession(long sessionId) {
    Set<Long> blockIds;
    mLock.lock();
    try {
      blockIds = mSessionIdToBlockIds.get(sessionId);
      if (blockIds == null) {
        return;
      }
    } finally {
      mLock.unlock();
    }

    for (Long blockId : blockIds) {
      try {
        // Note that we don't need to explicitly call abortBlock to cleanup the temp block
        // in Alluxio block store because they will be cleanup by the session cleaner in the
        // Alluxio block store.
        cleanup(sessionId, blockId);
        releaseAccess(sessionId, blockId);
      } catch (Exception e) {
        LOG.warn("Failed to cleanup UFS block {}, session {}.", blockId, sessionId);
      }
    }
  }

  /**
   * Creates a block reader that reads from UFS and optionally caches the block to the Alluxio
   * block store.
   *
   * @param sessionId the client session ID that requested this read
   * @param blockId the ID of the block to read
   * @param offset the read offset within the block (NOT the file)
   * @param noCache if set, do not try to cache the block in the Alluxio worker
   * @return the block reader instance
   * @throws BlockDoesNotExistException if the UFS block does not exist in the
   * {@link UnderFileSystemBlockStore}
   * @throws IOException if any I/O errors occur
   */
  public BlockReader getBlockReader(final long sessionId, long blockId, long offset,
      boolean noCache) throws BlockDoesNotExistException, IOException {
    final BlockInfo blockInfo;
    mLock.lock();
    try {
      blockInfo = getBlockInfo(sessionId, blockId);
      if (blockInfo.getBlockReader() != null) {
        return blockInfo.getBlockReader();
      }
    } finally {
      mLock.unlock();
    }
    BlockReader reader =
        UnderFileSystemBlockReader.create(blockInfo.getMeta(), offset, noCache, mAlluxioBlockStore);
    blockInfo.setBlockReader(reader);
    return reader;
  }

  /**
   * Gets the {@link UnderFileSystemBlockMeta} for a session ID and block ID pair.
   *
   * @param sessionId the session ID
   * @param blockId the block ID
   * @return the {@link UnderFileSystemBlockMeta} instance
   * @throws BlockDoesNotExistException if the UFS block does not exist in the
   * {@link UnderFileSystemBlockStore}
   */
  private BlockInfo getBlockInfo(long sessionId, long blockId)
      throws BlockDoesNotExistException {
    Key key = new Key(sessionId, blockId);
    BlockInfo blockInfo = mBlocks.get(key);
    if (blockInfo == null) {
      try {
        throw new BlockDoesNotExistException(ExceptionMessage.UFS_BLOCK_DOES_NOT_EXIST_FOR_SESSION,
            blockId, sessionId);
      } catch (Throwable e) {
        LOG.error("UFS Block does not exist.", e);
        throw e;
      }
    }
    return blockInfo;
  }

  private static class Key {
    private final long mSessionId;
    private final long mBlockId;

    /**
     * Creates an instance of the Key class.
     *
     * @param sessionId the session ID
     * @param blockId the block ID
     */
    public Key(long sessionId, long blockId) {
      mSessionId = sessionId;
      mBlockId = blockId;
    }

    /**
     * @return the block ID
     */
    public long getBlockId() {
      return mBlockId;
    }

    /**
     * @return the session ID
     */
    public long getSessionId() {
      return mSessionId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key)) {
        return false;
      }

      Key that = (Key) o;
      return Objects.equal(mBlockId, that.mBlockId) && Objects.equal(mSessionId, that.mSessionId);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(mBlockId, mSessionId);
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(this).add("blockId", mBlockId).add("sessionId", mSessionId)
          .toString();
    }
  }

  private static class BlockInfo {
    private final UnderFileSystemBlockMeta mMeta;

    // A correct client implementation should never access the following reader/writer
    // concurrently. But just to avoid crashing the server thread with runtime exception when
    // the client is mis-behaving, we access them with locks acquired.
    private BlockReader mBlockReader;
    private BlockWriter mBlockWriter;

    /**
     * Creates an instance of {@link BlockInfo}.
     *
     * @param meta the UFS block meta
     */
    public BlockInfo(UnderFileSystemBlockMeta meta) {
      mMeta = meta;
    }

    /**
     * @return the UFS block meta
     */
    public UnderFileSystemBlockMeta getMeta() {
      return mMeta;
    }

    /**
     * @return the cached the block reader if it is not closed
     */
    public synchronized BlockReader getBlockReader() {
      if (mBlockReader != null && mBlockReader.isClosed()) {
        mBlockReader = null;
      }
      return mBlockReader;
    }

    /**
     * @param blockReader the block reader to be set
     */
    public synchronized void setBlockReader(BlockReader blockReader) {
      mBlockReader = blockReader;
    }

    /**
     * @return the block writer
     */
    public synchronized BlockWriter getBlockWriter() {
      return mBlockWriter;
    }

    /**
     * @param blockWriter the block writer to be set
     */
    public synchronized void setBlockWriter(BlockWriter blockWriter) {
      mBlockWriter = blockWriter;
    }

    /**
     * Closes the block reader or writer.
     *
     * @throws IOException if it fails to close block reader or writer
     */
    public synchronized void closeReaderOrWriter() throws IOException {
      if (mBlockReader != null) {
        mBlockReader.close();
        mBlockReader = null;
      }
      if (mBlockWriter != null) {
        mBlockWriter.close();
        mBlockWriter = null;
      }
    }
  }
}
