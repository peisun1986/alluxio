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

package alluxio.client.block.stream;

import io.netty.buffer.ByteBuf;

import java.io.Closeable;
import java.io.IOException;

/**
 * The interface to read remote block from data server.
 */
public interface PacketReader extends Closeable {

  /**
   * Reads a packet.
   *
   * @return the data buffer or null if EOF is reached
   * @throws IOException if it fails to read a packet
   */
  ByteBuf readPacket() throws IOException;

  long pos();

  @Override
  void close();
}