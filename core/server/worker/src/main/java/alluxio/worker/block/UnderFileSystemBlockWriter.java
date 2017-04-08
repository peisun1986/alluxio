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

import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.options.CreateOptions;
import alluxio.util.network.NetworkAddressUtils;
import alluxio.worker.block.io.BlockWriter;
import alluxio.worker.block.meta.UnderFileSystemBlockMeta;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.WritableByteChannel;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
public final class UnderFileSystemBlockWriter implements BlockWriter {
  private final String mUfsPath;

  private OutputStream mUnderFileSystemOutputStream;
  private long mPos;

  /** If set, the writer is closed and should not be used afterwards. */
  private boolean mClosed;

  /**
   * Creates an instance of {@link UnderFileSystemBlockWriter}.
   *
   * @param path the UFS path
   * @param options the options to create the file in UFS
   * @return the UFS block writer instance
   * @throws IOException if any I/O related errors occur
   */
  public static UnderFileSystemBlockWriter create(String path, CreateOptions options)
      throws IOException {
    UnderFileSystemBlockWriter writer = new UnderFileSystemBlockWriter(path);
    writer.init(options);
    return writer;
  }

  /**
   * Constructs an instance of {@link UnderFileSystemBlockWriter}.
   *
   * @param path the UFS path
   */
  private UnderFileSystemBlockWriter(String path) {
    mUfsPath = path;
  }

  /**
   * Initializes the block writer by connecting to the UFS and creating the file in UFS.
   *
   * @param options the options to create the file
   * @throws IOException if any I/O related errors occur
   */
  private void init(CreateOptions options) throws IOException {
    UnderFileSystem ufs = UnderFileSystem.Factory.get(mUfsPath);
    ufs.connectFromWorker(
        NetworkAddressUtils.getConnectHost(NetworkAddressUtils.ServiceType.WORKER_RPC));

    mUnderFileSystemOutputStream = ufs.create(mUfsPath, options);
  }

  @Override
  public GatheringByteChannel getChannel() {
    throw new UnsupportedOperationException("UFSFileBlockWriter#getChannel is not supported");
  }

  @Override
  public long append(ByteBuffer buffer) throws IOException {
    Preconditions.checkState(!mClosed);
    WritableByteChannel channel = Channels.newChannel(mUnderFileSystemOutputStream);
    long bytesWritten = 0;
    while (buffer.remaining() > 0) {
      bytesWritten += channel.write(buffer);
    }
    mPos += bytesWritten;
    return bytesWritten;
  }

  @Override
  public void transferFrom(ByteBuf buf) throws IOException {
    Preconditions.checkState(!mClosed);
    int bytesToTransfer = buf.readableBytes();
    while (buf.readableBytes() > 0) {
      buf.readBytes(mUnderFileSystemOutputStream, buf.readableBytes());
    }
    mPos += bytesToTransfer;
  }

  @Override
  public void cancel() throws IOException {
    // This cancel approach has a potential race condition with the following event order:
    // 1. The client cancels the file. The worker thread that executes this cancel is context
    //    switched after mUnderFileSystemOutputStream.close().
    // 2. The client is dead and retries. It deletes and writes the file successfully.
    // 3. The server finishes the initial cancel request by deleting the file. The content
    //    written in step 2 is gone.
    // The way to fix this is to have the UnderFileSystem to implement cancel correctly.
    try {
      mUnderFileSystemOutputStream.close();
    } finally {
      UnderFileSystem ufs = UnderFileSystem.Factory.get(mUfsPath);
      // TODO(calvin): Log a warning if the delete fails
      ufs.deleteFile(mUfsPath);
    }
    mClosed = true;
  }

  /**
   * Closes the block reader. After this, this block reader should not be used anymore.
   * This is recommended to be called after the client finishes reading the block. It is usually
   * triggered when the client unlocks the block.
   *
   * @throws IOException if any I/O errors occur
   */
  @Override
  public void close() throws IOException {
    if (mClosed) {
      return;
    }
    mUnderFileSystemOutputStream.close();
    mClosed = true;
  }

  @Override
  public long getPosition() {
    return mPos;
  }
}

