package playground.client

import java.util.concurrent.ArrayBlockingQueue

import base.LogSupport
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelFuture, ChannelHandlerContext, ChannelInitializer, SimpleChannelInboundHandler}
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.{ImmediateEventExecutor, Promise, Future => NFuture}

trait HttpClient {
  type Request
  type Response

  def execute(request: Request): NFuture[Response]

  def connection: ChannelFuture

  def get(uri: String): NFuture[FullHttpResponse]
  def post(uri: String, content: Option[ByteBuf]): NFuture[FullHttpResponse]
}

class DefaultHttpClient(private val bootstrap: Bootstrap)(host: String, port: Int) extends HttpClient {

  type Request = HttpReq
  type Response = FullHttpResponse

  private val requestsQueue = new ArrayBlockingQueue[RequestHandler](20)
  protected val responseHandler = new HttpClientResponseHandler[this.Response](_.asInstanceOf[FullHttpResponse], onResponseCompletedEvent)

  private val _boot = bootstrap
    .handler(new ChannelInitializer[SocketChannel]() {
      def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        p.addLast(new HttpClientCodec())
        p.addLast(new HttpObjectAggregator(10000))
        p.addLast(responseHandler)
      }
    })

  @volatile private var connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected
  @volatile private var connectionChannel: Option[ChannelFuture] = None

  def get(uri: String): NFuture[FullHttpResponse] = {
    val request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri)
    HttpHeaders.setContentLength(request, 0)
    this.execute(HttpReq(Seq(request)))
  }

  def post(uri: String, content: Option[ByteBuf]): NFuture[FullHttpResponse] = {
    val request = content match {
      case Some(c) => new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, c)
      case None => new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri)
    }
    HttpHeaders.setContentLength(request, content.map(_.readableBytes()).getOrElse(0).toLong)
    this.execute(HttpReq(Seq(request)))
  }

  def connection: ChannelFuture = synchronized {
    connectionChannel.getOrElse {
      val channelFuture = this.connect()
      connectionChannel = Some(channelFuture)
      channelFuture
    }
  }

  protected def connect(): ChannelFuture = {
    val channel = _boot.connect(host, port)
    channel.channel().closeFuture().addListener((_: ChannelFuture) => connectionChannel = None)
    channel
  }

  protected def onResponseCompletedEvent(): Unit = {
    connectionStatus = ConnectionStatus.Idle
    pollNextRequest()
  }

  def execute(request: HttpRequest): NFuture[this.Response] = {
    this.execute(HttpReq(Seq(request)))
  }

  def execute(request: this.Request): NFuture[this.Response] = {
    val result: Promise[this.Response] = ImmediateEventExecutor.INSTANCE.newPromise.asInstanceOf[Promise[this.Response]]
    requestsQueue.add(RequestHandler(request, result))
    pollNextRequest()
    result
  }

  protected def pollNextRequest(): Unit = {
    connectionStatus match {
      case ConnectionStatus.Disconnected | ConnectionStatus.Idle =>
        val r = requestsQueue.poll()
        if (r != null) {
          responseHandler.subscribeHandler(r.promise)
          executeRequest(r.request)
        }
      case _ =>
    }
  }

  private def executeRequest(request: this.Request): Unit = {
    connection.addListener((_: ChannelFuture) => {
      val channel = connection.channel()
      request.content.foreach(channel.write)
      channel.flush()
    })
  }

  case class RequestHandler(request: Request, promise: Promise[Response])

  trait ConnectionStatus
  object ConnectionStatus {
    case object Disconnected extends ConnectionStatus
    case object Idle extends ConnectionStatus
    case object Busy extends ConnectionStatus
  }

  class HttpClientResponseHandler[A](responseBuilder: HttpObject => A, responseCompletedEventHandler: () => Unit) extends SimpleChannelInboundHandler[HttpObject] with LogSupport {

    private var _promise: Option[Promise[A]] = None
    def subscribeHandler(promise: Promise[A]): Unit = {
      _promise = Some(promise)
    }

    def onResponseArriveEvent(msg: HttpObject): Unit = {
      _promise.foreach(_.setSuccess(responseBuilder(msg)))
    }
    def onResponseCompletedEvent(): Unit = responseCompletedEventHandler()

    override def channelRead0(ctx: ChannelHandlerContext, msg: HttpObject): Unit = {

      if (msg.isInstanceOf[HttpResponse]) {
        this.onResponseArriveEvent(msg)
        val response = msg.asInstanceOf[HttpResponse]
        log.debug(s"Response: $response")
        if (HttpHeaders.isTransferEncodingChunked(response)) log.debug("CHUNKED CONTENT {")
        else log.debug("CONTENT {")
      }
      if (msg.isInstanceOf[HttpContent]) {
        val content = msg.asInstanceOf[HttpContent]
        log.debug(content.content.toString(CharsetUtil.UTF_8))
      }
      if (msg.isInstanceOf[LastHttpContent]) {
        log.debug("} END OF CONTENT")
        this.onResponseCompletedEvent()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
      cause.printStackTrace()
      ctx.close
    }
  }

  case class HttpReq(content: Seq[AnyRef], keepAlive: Boolean = true)
}
