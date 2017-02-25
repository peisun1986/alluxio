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

package alluxio.client;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.LocalAlluxioClusterResource;
import alluxio.PropertyKey;
import alluxio.client.block.UnderFileSystemBlockInStream;
import alluxio.client.file.FileInStream;
import alluxio.client.file.FileOutStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.options.CreateFileOptions;
import alluxio.client.file.options.OpenFileOptions;
import alluxio.util.io.BufferUtils;
import alluxio.util.io.PathUtils;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests for {@link alluxio.client.block.UnderFileSystemBlockInStream}.
 */
public class UnderFileSystemBlockInStreamIntegrationTest {
  private static final Logger LOG =
      LoggerFactory.getLogger(UnderFileSystemBlockInStreamIntegrationTest.class);
  private static final int MIN_LEN = 0;
  private static final int MAX_LEN = 255;
  private static final int DELTA = 33;

  private FileSystem mFileSystem = null;
  private CreateFileOptions mWriteUnderStore;
  private OpenFileOptions mReadNoCache;
  private OpenFileOptions mReadCache;

  @Rule
  public LocalAlluxioClusterResource mLocalAlluxioClusterResource =
      new LocalAlluxioClusterResource.Builder()
          .setProperty(PropertyKey.USER_BLOCK_REMOTE_READ_BUFFER_SIZE_BYTES, "100")
          .setProperty(PropertyKey.USER_UFS_BLOCK_MAX_READ_CONCURRENCY_DEFAULT, 2).build();

  @Rule
  public ExpectedException mThrown = ExpectedException.none();

  @Before
  public final void before() throws Exception {
    mFileSystem = mLocalAlluxioClusterResource.get().getClient();
    mWriteUnderStore = CreateFileOptions.defaults().setWriteType(WriteType.THROUGH);
    mReadCache = OpenFileOptions.defaults().setReadType(ReadType.CACHE_PROMOTE);
    mReadNoCache = OpenFileOptions.defaults().setReadType(ReadType.NO_CACHE);
  }

  /**
   * Tests {@link UnderFileSystemBlockInStream#read()}. Read from underfs.
   */
  @Test
  public void read() throws Exception {
    String uniqPath = PathUtils.uniqPath();
    for (int k = MIN_LEN; k <= MAX_LEN; k += DELTA) {
      AlluxioURI uri = new AlluxioURI(uniqPath + "/file_" + k);
      FileSystemTestUtils.createByteFile(mFileSystem, uri, mWriteUnderStore, k);

      FileInStream is = mFileSystem.openFile(uri, mReadNoCache);
      byte[] ret = new byte[k];
      int value = is.read();
      int cnt = 0;
      while (value != -1) {
        Assert.assertTrue(value >= 0);
        Assert.assertTrue(value < 256);
        ret[cnt++] = (byte) value;
        value = is.read();
      }
      Assert.assertEquals(cnt, k);
      Assert.assertTrue(BufferUtils.equalIncreasingByteArray(k, ret));
      is.close();
      if (k == 0) {
        Assert.assertEquals(100, mFileSystem.getStatus(uri).getInMemoryPercentage());
      } else {
        Assert.assertNotEquals(100, mFileSystem.getStatus(uri).getInMemoryPercentage());
      }

      is = mFileSystem.openFile(uri, mReadCache);
      ret = new byte[k];
      value = is.read();
      cnt = 0;
      while (value != -1) {
        Assert.assertTrue(value >= 0);
        Assert.assertTrue(value < 256);
        ret[cnt++] = (byte) value;
        value = is.read();
      }
      Assert.assertEquals(cnt, k);
      Assert.assertTrue(BufferUtils.equalIncreasingByteArray(k, ret));
      is.close();
      Assert.assertEquals(100, mFileSystem.getStatus(uri).getInMemoryPercentage());

      is = mFileSystem.openFile(uri, mReadCache);
      ret = new byte[k];
      value = is.read();
      cnt = 0;
      while (value != -1) {
        Assert.assertTrue(value >= 0);
        Assert.assertTrue(value < 256);
        ret[cnt++] = (byte) value;
        value = is.read();
      }
      Assert.assertEquals(cnt, k);
      Assert.assertTrue(BufferUtils.equalIncreasingByteArray(k, ret));
      is.close();
      Assert.assertEquals(100, mFileSystem.getStatus(uri).getInMemoryPercentage());
    }
  }

  /**
   * Tests {@link UnderFileSystemBlockInStream#read()} concurrency. Read from underfs.
   */
  @Test
  public void concurrentUfsRead() throws Exception {
    String uniqPath = PathUtils.uniqPath();
    final AlluxioURI uri = new AlluxioURI(uniqPath + "/file_" + MAX_LEN);
    FileSystemTestUtils.createByteFile(mFileSystem, uri, mWriteUnderStore, MAX_LEN);

    ExecutorService executorService = Executors.newFixedThreadPool(100);

    final AtomicInteger count = new AtomicInteger(0);
    final Random random = new Random();
    int expectedCount = 100;
    for (int i = 0; i < expectedCount; i++) {
      final int index = i;
      executorService.submit(new Runnable() {
        @Override
        public void run() {
          try {
            // Add some randomness here to avoid opening too many connections too fast.
            Thread.sleep(random.nextInt(100));
            FileInStream is = mFileSystem.openFile(uri, mReadCache);

            // Sleep here so that we can make sure the file is not cached too fast.
            Thread.sleep(100);
            byte[] ret = new byte[MAX_LEN];
            int value = is.read();
            int cnt = 0;
            while (value != -1) {
              Assert.assertTrue(value >= 0);
              Assert.assertTrue(value < 256);
              ret[cnt++] = (byte) value;
              value = is.read();
            }
            is.close();
            Assert.assertEquals(cnt, MAX_LEN);
            Assert.assertTrue(BufferUtils.equalIncreasingByteArray(MAX_LEN, ret));
            while (mFileSystem.getStatus(uri).getInMemoryPercentage() < 100) {
              Thread.sleep(1000);
            }
            Assert.assertEquals(100, mFileSystem.getStatus(uri).getInMemoryPercentage());
            count.incrementAndGet();
          } catch (Throwable e) {
            LOG.error("Failed to read file {}.", index, e);
          }
        }
      });
    }
    executorService.shutdown();
    executorService.awaitTermination(Constants.MINUTE_MS * 5, TimeUnit.MILLISECONDS);
    // There can be some errors due because SASL handsake failure or opening the connections too
    // fast. If that happens, consider loosing thie condition a bit.
    Assert.assertEquals(expectedCount, count.get());
  }

  /**
   * Tests {@link UnderFileSystemBlockInStream#seek(long)}.
   */
  @Test
  public void seek() throws Exception {
    String uniqPath = PathUtils.uniqPath();
    for (int k = MIN_LEN + DELTA; k <= MAX_LEN; k += DELTA) {
      AlluxioURI uri = new AlluxioURI(uniqPath + "/file_" + k);
      FileSystemTestUtils.createByteFile(mFileSystem, uri, mWriteUnderStore, k);

      FileInStream is = mFileSystem.openFile(uri, mReadCache);

      Assert.assertEquals(0, is.read());
      is.seek(k / 3);
      Assert.assertEquals(k / 3, is.read());
      is.seek(k / 2);
      Assert.assertEquals(k / 2, is.read());
      is.seek(k / 4);
      Assert.assertEquals(k / 4, is.read());
      is.close();
    }
  }

  /**
   * Tests {@link UnderFileSystemBlockInStream#skip(long)}.
   */
  @Test
  public void skip() throws Exception {
    String uniqPath = PathUtils.uniqPath();
    for (int k = MIN_LEN + DELTA; k <= MAX_LEN; k += DELTA) {
      AlluxioURI uri = new AlluxioURI(uniqPath + "/file_" + k);
      FileSystemTestUtils.createByteFile(mFileSystem, uri, mWriteUnderStore, k);

      FileInStream is = mFileSystem.openFile(uri, mReadCache);
      Assert.assertEquals(k / 2, is.skip(k / 2));
      Assert.assertEquals(k / 2, is.read());
      is.close();
      Assert.assertEquals(100, mFileSystem.getStatus(uri).getInMemoryPercentage());

      if (k >= 3) {
        is = mFileSystem.openFile(uri, mReadCache);
        int t = k / 3;
        Assert.assertEquals(t, is.skip(t));
        Assert.assertEquals(t, is.read());
        Assert.assertEquals(t, is.skip(t));
        Assert.assertEquals(2 * t + 1, is.read());
        is.close();
        Assert.assertTrue(mFileSystem.getStatus(uri).getInMemoryPercentage() == 100);
      }
    }
  }

  /**
   * Tests that reading a file consisting of more than one block from the underfs works.
   */
  @Test
  public void readMultiBlockFile() throws Exception {
    String uniqPath = PathUtils.uniqPath();
    int blockSizeByte = 10;
    int numBlocks = 10;
    AlluxioURI uri = new AlluxioURI(uniqPath);
    FileOutStream os = mFileSystem.createFile(uri, mWriteUnderStore);
    for (int i = 0; i < numBlocks; i++) {
      for (int j = 0; j < blockSizeByte; j++) {
        os.write((byte) (i * blockSizeByte + j));
      }
    }
    os.close();

    FileInStream is = mFileSystem.openFile(uri, mReadCache);
    for (int i = 0; i < blockSizeByte * numBlocks; i++) {
      Assert.assertEquals((byte) i, is.read());
    }
    is.close();
    Assert.assertTrue(mFileSystem.getStatus(uri).getInMemoryPercentage() == 100);
  }
}