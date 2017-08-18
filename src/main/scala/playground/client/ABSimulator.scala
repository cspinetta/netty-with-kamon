package playground.client

import java.lang

import base.LogSupport
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.{Future => NFuture}

import scala.util.Random


case class ABSimulator(count: Int, parallel: Int) extends LogSupport {

  import base.NettySugar.syntax._
  import playground.client.ABSimulator._

  val random = new Random()

  def start(host: String = "127.0.0.1", port: Int = 8080): Unit = {

    val workerGroup = new NioEventLoopGroup(parallel)

    try {
      val channels: Seq[NFuture[_]] = (1 to parallel).map(i => taskThread())
      channels.foreach(_.await(10000))
      log.info("Client finished successfully")
    } finally {
      workerGroup.shutdownGracefully()
    }

    def taskThread(): NFuture[_] = {
      log.debug(s"Creating Bootstrap...")

      val boot = new Bootstrap()
      boot.group(workerGroup)
        .channel(classOf[NioSocketChannel])
        .handler(new ChannelInitializer[SocketChannel]() {
          def initChannel(ch: SocketChannel) {
            val p = ch.pipeline()
            p.addLast(new HttpClientCodec())
          }
        })
        .option[lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

      val client = new DefaultHttpClient(boot)(host, port)

      val lastResponse = (1 until count).foldLeft(randomRequest(client)) { case (responseFut, _) =>
        responseFut
          .map(response => println(response.content()))
          .flatMap(_ => randomRequest(client))
      }
      lastResponse.map(_ => client.connection.channel().closeFuture())
    }
  }

  def randomRequest(client: HttpClient): NFuture[FullHttpResponse] = {
    ABSimulator.possibleRequests(random.nextInt(ABSimulator.possibleRequests.size)) match {
      case GetRequestBuilder(uri) => client.get(uri)
      case PostRequestBuilder(uri, content) => client.post(uri, content.map( c =>
        Unpooled.copiedBuffer(c, CharsetUtil.UTF_8)))
    }
  }

}

object ABSimulator {
  val possibleRequests = List(
    GetRequestBuilder("/hello"),
    GetRequestBuilder("/good-bye"),
    PostRequestBuilder("/create", Some("user 1")))

  trait RequestBuilder
  case class GetRequestBuilder(uri: String) extends RequestBuilder
  case class PostRequestBuilder(uri: String, content: Option[String]) extends RequestBuilder
}
