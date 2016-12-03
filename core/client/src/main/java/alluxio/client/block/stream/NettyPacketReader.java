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

import alluxio.Constants;
import alluxio.client.block.BlockStoreContext;
import alluxio.network.protocol.RPCBlockReadRequest;
import alluxio.network.protocol.RPCBlockReadResponse;
import alluxio.network.protocol.RPCResponse;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * A netty block reader that streams a block region from a netty data server.
 *
 * Protocol:
 * 1. The client sends a read request (blockId, offset, length).
 * 2. Once the server receives the request, it streams packets the client. The streaming pauses
 *    if the server's buffer is full and resumes if the buffer is not full.
 * 3. The client reads packets from the stream. Reading pauses if the client buffer is full and
 *    resumes if the buffer is not full. If the client can keep up with network speed, the buffer
 *    should have at most one packet.
 * 4. The client stops reading if it receives an empty packe which signifies the end of the block
 *    streaming.
 * 5. The client can cancel the read request at anytime. The cancel request is ignored by the
 *    server if everything has been sent to channel.
 * 6. In order to reuse the channel, the client must read all the packets in the channel before
 *    releasing the channel to the channel pool.
 * 7. To make it simple to handle errors, the channel is closed if any error occurs.
 */
@NotThreadSafe
public final class NettyPacketReader extends AbstractPacketReader {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  private final Channel mChannel;

  /**
   * Creates an instance of {@link NettyPacketReader}.
   *
   * @param address the netty data server network address
   * @param id the block ID or UFS file ID
   * @param offset the offset
   * @param len the length to read
   * @param lockId the lock ID
   * @param sessionId the session ID
   * @throws IOException if it fails to create the object
   */
  private NettyPacketReader(InetSocketAddress address, long id, long offset, int len,
      long lockId, long sessionId) throws IOException {
    super(address,id, offset, len);

    mChannel = BlockStoreContext.acquireNettyChannel(address);
    mChannel.pipeline().addLast(new Handler());
    mChannel.writeAndFlush(new RPCBlockReadRequest(mId, offset, len, lockId, sessionId))
        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
  }

  public NettyPacketReader createBlockReader(InetSocketAddress address, long id, long offset, int len,
      long lockId, long sessionId) throws IOException {
    return new NettyPacketReader(address, id, offset, len, lockId, sessionId);
  }

  public NettyPacketReader createFileReader(InetSocketAddress address, long id, long offset,
      int len) throws IOException {
    return new NettyPacketReader(address, id, offset, len, -1, -1);
  }

  @Override
  public void close() {
    try {
      if (mDone) {
        return;
      }
      if (!mChannel.isOpen()) {
        return;
      }
      try {
        ChannelFuture channelFuture =
            mChannel.writeAndFlush(RPCBlockReadRequest.createCancelRequest(mId));
        channelFuture.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        channelFuture.sync();
      } catch (InterruptedException e) {
        mChannel.close();
        throw Throwables.propagate(e);
      }

      while (true) {
        try {
          ByteBuf buf = readPacket();
          if (buf == null) {
            return;
          }
          ReferenceCountUtil.release(buf);
        } catch (IOException e) {
          LOG.warn("Failed to close the NettyBlockReader (block: {}, address: {}).",
              mId, mAddress, e);
          mChannel.close();
          return;
        }
      }
    } finally {
      if (mChannel.isOpen()) {
        Preconditions.checkState(mChannel.pipeline().last() instanceof Handler);
        mChannel.pipeline().removeLast();
      }
      BlockStoreContext.releaseNettyChannel(mAddress, mChannel);
    }
  }

  /**
   * The netty handler that reads packets from the channel.
   */
  public class Handler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws IOException {
      Preconditions.checkState(msg instanceof RPCBlockReadResponse, "Incorrect response type.");
      RPCBlockReadResponse response = (RPCBlockReadResponse) msg;
      if (response.getStatus() != RPCResponse.Status.SUCCESS) {
        throw new IOException(String
            .format("Failed to read block %d from %s with status %s.", mId, mAddress,
                response.getStatus().getMessage()));
      }
      mLock.lock();
      try {
        Preconditions.checkState(mPacketReaderException == null);
        ByteBuf buf = response.getPayloadData();
        Preconditions.checkState(mPackets.offer(buf));
        mNotEmptyOrFail.signal();

        if (tooManyPacketsPending()) {
          pause();
        }
      } finally {
        mLock.unlock();
        ReferenceCountUtil.release(response.getPayloadData());
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      LOG.error("Exception caught while reading response from netty channel {}.",
          cause.getMessage());
      mLock.lock();
      try {
        mPacketReaderException = cause;
        mNotEmptyOrFail.signal();
      } finally {
        mLock.unlock();
      }
      ctx.close();
    }
  }

  @Override
  protected void pause() {
    mChannel.config().setAutoRead(false);
  }

  @Override
  protected void resume() {
    mChannel.config().setAutoRead(true);
    mChannel.read();
  }
}
