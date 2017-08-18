package playground.client

import base.{ConfigSupport, LogSupport}
import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.{Future => NFuture}
import kamon.Kamon
import kamon.context.Context
import kamon.trace.Span

import scala.util.Random


case class ABSimulator(count: Int, parallel: Int) extends LogSupport {

  import base.NettySugar.syntax._
  import playground.client.ABSimulator._

  val random = new Random()

  def start(host: String, port: Int): Unit = {

    val workerGroup = new NioEventLoopGroup(parallel)

    try {
      val futures: Seq[NFuture[_]] = (1 to parallel).map(i => taskThread(workerGroup)(host, port))
      futures.foreach(_.await(100000))
      log.info("Client finished successfully")
    } finally {
      workerGroup.shutdownGracefully()
    }
  }

  def taskThread(workerGroup: NioEventLoopGroup)(host: String, port: Int): NFuture[_] = {
    log.debug(s"Starting task thread to perform $count requests to $host:$port ...")
    val client = DefaultHttpClient.withNio(workerGroup)(host, port)

    val clientSpan = Kamon.buildSpan("client-span").start()
    Kamon.withContext(Context.create(Span.ContextKey, clientSpan)) {
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

object ABSimulator extends ConfigSupport {
  val possibleRequests: List[RequestBuilder] = config.requestGenerator.requests.map(req => {
    if (req.method == "get") GetRequestBuilder(req.uri)
    else PostRequestBuilder(req.uri, req.content)
  })

  trait RequestBuilder
  case class GetRequestBuilder(uri: String) extends RequestBuilder
  case class PostRequestBuilder(uri: String, content: Option[String]) extends RequestBuilder
}
