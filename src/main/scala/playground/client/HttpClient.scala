package playground.client

import java.lang
import java.util.concurrent.atomic.AtomicLong

import base.LogSupport
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.GenericFutureListener

import scala.annotation.tailrec
import scala.collection.immutable


case class HttpClient(count: Int = 10, parallel: Int = 10) extends LogSupport {

  import base.NettySugar.syntax._

  def start(host: String = "127.0.0.1", port: Int = 8080): Unit = {

    val workerGroup = new NioEventLoopGroup(parallel)

    try {
      val channels: Seq[ChannelFuture] = (1 to parallel).map(i => taskThread())
      channels.foreach(_.channel().closeFuture().syncUninterruptibly())
      log.info("Client finished successfully")
    } finally {
      workerGroup.shutdownGracefully()
    }

    def taskThread(): ChannelFuture = {
      val requestGenerator = new RequestGenerator(host, count)
      log.debug(s"Creating Bootstrap...")
      val boot = new Bootstrap()
      boot.group(workerGroup)
        .channel(classOf[NioSocketChannel])
        .handler(new ChannelInitializer[SocketChannel]() {
          def initChannel(ch: SocketChannel) {
            val p = ch.pipeline()
            p.addLast(new HttpClientCodec())
            p.addLast(new HttpClientHandler(requestGenerator))
          }
        })
        .option[lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

      log.debug("Connect to server")

      val channelFuture: ChannelFuture = boot.connect(host, port)

      channelFuture.flatMap(_.writeAndFlush(requestGenerator.buildRequest()))

      channelFuture
    }
  }
}

class HttpClientHandler(requestGenerator: RequestGenerator) extends SimpleChannelInboundHandler[HttpObject] with LogSupport {
  import scala.collection.JavaConverters._

  override def channelRead0(ctx: ChannelHandlerContext, msg: HttpObject): Unit = {

    if (msg.isInstanceOf[HttpResponse]) {
      val response = msg.asInstanceOf[HttpResponse]

      log.debug(s"Response: $response")

      if (HttpHeaders.isTransferEncodingChunked(response)) log.debug("CHUNKED CONTENT {")
      else log.debug("CONTENT {")
    }
    if (msg.isInstanceOf[HttpContent]) {
      val content = msg.asInstanceOf[HttpContent]
      log.debug(content.content.toString(CharsetUtil.UTF_8))
      if (content.isInstanceOf[LastHttpContent]) {
        log.debug("} END OF CONTENT")

        requestGenerator.performRequest(ctx.channel())

        ctx.channel().flush()
      }
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    cause.printStackTrace()
    ctx.close
  }
}

class RequestGenerator(host: String, _count: Int = 2) {

  private val count = new AtomicLong(_count)

  def performRequest(channel: Channel): Unit = {
    val current = count.getAndDecrement()
    if (current > 0) {
      channel.write(this.buildRequest())
    } else channel.close()
  }

  def buildRequest(keepAlive: Boolean = true): FullHttpRequest = {
    val keepAliveValue = if (keepAlive) HttpHeaders.Values.KEEP_ALIVE else HttpHeaders.Values.CLOSE
    val request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
    request.headers().set(HttpHeaders.Names.HOST, host)
    request.headers().set(HttpHeaders.Names.CONNECTION, keepAliveValue)
    request.headers().set(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP)
//    insertSpan(request.headers(), nextSequence)
    request
  }

//  private def insertSpan(headers: HttpHeaders, nextSequence: Int): Unit = {
//    import ExtendedB3.Headers._
//    headers.set(TraceIdentifier, "111")
//    headers.set(SpanIdentifier, nextSequence)
//    //    headers.set(ParentSpanIdentifier, nextSequence)
//  }
}