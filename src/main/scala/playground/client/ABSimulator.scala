package playground.client

import base.{ConfigSupport, LogSupport}
import io.netty.buffer.Unpooled
import io.netty.channel.MultithreadEventLoopGroup
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.{Future => NFuture}
import kamon.Kamon
import kamon.context.{Context => KamonContext}
import kamon.trace.Span

import scala.util.Random


case class ABSimulator(count: Int, parallel: Int) extends LogSupport with ConfigSupport {

  import base.NettySugar.syntax._
  import playground.client.ABSimulator._

  val random = new Random()

  def start(host: String, port: Int): Unit = {

    val (workerGroup, clientBuilder) =
      if (Epoll.isAvailable) {
        log.info(s"Using Epoll Event Loop")
        val el = new EpollEventLoopGroup(parallel)
        (el, DefaultHttpClient.withEpoll(el) _)
      } else {
        log.info(s"Using Nio Event Loop")
        val el = new NioEventLoopGroup(parallel)
        (el, DefaultHttpClient.withNio(el) _)
      }

    try {
      val futures: Seq[NFuture[_]] = (1 to parallel).map(_ => taskThread(workerGroup, clientBuilder)(host, port))
      futures.foreach(_.sync())
      log.info("All client have finished successfully")
    } finally {
      workerGroup.shutdownGracefully()
    }
  }

  def taskThread(workerGroup: MultithreadEventLoopGroup, clientBuilder: (String, Int) => DefaultHttpClient)(host: String, port: Int): NFuture[_] = {
    log.debug(s"Starting task thread to perform $count requests to $host:$port ...")

    implicit val kamonContext = if (config.requestGenerator.kamonEnabled) {
      val clientSpan = Kamon.buildSpan("client-span").start()
      KamonContext.create(Span.ContextKey, clientSpan)
    } else KamonContext.Empty

    val client = clientBuilder(host, port)

    val lastResponse = (1 until count).foldLeft(randomRequest(client)) { case (responseFut, _) =>
      responseFut
        .map(response => log.debug(s"-----------------> Response: \n${response.content().toString(CharsetUtil.UTF_8)}"))
        .flatMap(_ => randomRequest(client))
    }
    lastResponse.flatMap(response => {
      log.debug(s"-----------------> Response (LAST!): \n${response.content().toString(CharsetUtil.UTF_8)}")
      log.debug(s"Task's finished successfully")
      client.close()
    })
  }

  def randomRequest(client: HttpClient)(implicit kamonContext: KamonContext): NFuture[FullHttpResponse] =
    Kamon.withContext(kamonContext) {
      ABSimulator.possibleRequests(random.nextInt(ABSimulator.possibleRequests.size)) match {
        case GetRequestBuilder(uri) =>
          log.debug(s"-----------------> Request: GET:$uri")
          client.get(uri)
        case PostRequestBuilder(uri, content) =>
          log.debug(s"-----------------> Request: POST:$uri")
          client.post(uri, content.map( c => Unpooled.copiedBuffer(c, CharsetUtil.UTF_8)))
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
