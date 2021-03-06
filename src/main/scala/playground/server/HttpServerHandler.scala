package playground
package server

import base.LogSupport
import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.HttpHeaders.Names._
import io.netty.handler.codec.http.HttpHeaders._
import io.netty.handler.codec.http.HttpResponseStatus._
import io.netty.handler.codec.http.HttpVersion._
import io.netty.handler.codec.http.{DefaultFullHttpResponse, HttpRequest}
import io.netty.util.CharsetUtil

class HttpServerHandler() extends ChannelInboundHandlerAdapter with LogSupport {

  import HttpServerHandler.CONTENT

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
    msg match {
      case req: HttpRequest =>
        log.debug(s"Request: $req")
        if (is100ContinueExpected(req)) {
          ctx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE))
        }
        val keepAlive = isKeepAlive(req)
        val response = new DefaultFullHttpResponse(HTTP_1_1, OK, CONTENT.duplicate()) <| { r =>
          r.headers().set(CONTENT_TYPE, "text/plain")
          r.headers().set(CONTENT_LENGTH, r.content().readableBytes())
          if (keepAlive) r.headers().set(CONNECTION, Values.KEEP_ALIVE)
        }

        val future = ctx.write(response)
        if (!keepAlive) future.closeOnComplete()

      case _ =>
    }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    ctx.flush()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    cause.printStackTrace()
    ctx.close()
  }
}

object HttpServerHandler {
  private final val CONTENT =
    Unpooled.unreleasableBuffer(Unpooled.copiedBuffer("Hello World!", CharsetUtil.US_ASCII))
}
