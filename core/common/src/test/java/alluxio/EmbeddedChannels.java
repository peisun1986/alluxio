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

package alluxio;

import com.google.common.base.Throwables;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Queue;

/**
 * Special versions of {@link EmbeddedChannel}.
 */
public final class EmbeddedChannels {
  private EmbeddedChannels() {}  // prevent instantiation

  public static final class EmbeddedChannelEmptyCtor extends EmbeddedChannel {
    public EmbeddedChannelEmptyCtor() {
      // Invoke the parent ctor with a dummy handler.
      super(new ChannelInboundHandlerAdapter());
      // Remove the dummy handler.
      pipeline().removeFirst();
    }

    public void finishChannelCreation() {
      pipeline().removeFirst();
      pipeline().addLast(new LastInboundHandler());
    }

    private Queue<Object> getInboundMessages() {
      try {
        Field field = getClass().getField("inboundMessages");
        field.setAccessible(true);
        return (Queue<Object>) field.get(this);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw Throwables.propagate(e);
      }
    }

    private void recordException(Throwable e) {
      try {
        Method method = getClass().getMethod("recordException");
        method.setAccessible(true);
        method.invoke(this, e);
      } catch (Exception ee) {
        throw Throwables.propagate(ee);
      }
    }

    private final class LastInboundHandler extends ChannelInboundHandlerAdapter {
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        getInboundMessages().add(msg);
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        recordException(cause);
      }
    }
  }
}
