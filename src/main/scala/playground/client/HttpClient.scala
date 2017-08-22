package playground.client

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import java.{lang, util}

import base.LogSupport
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.channel.epoll.{EpollEventLoopGroup, EpollSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.{ImmediateEventExecutor, Promise, Future => NFuture}
import kamon.netty.instrumentation.ChannelContextAware

trait HttpClient {
  type Request
  type Response

  def addRequest(request: Request): NFuture[Response]

  def connection: ChannelFuture
  def close: NFuture[Unit]

  def get(uri: String): NFuture[FullHttpResponse]
  def post(uri: String, content: Option[ByteBuf]): NFuture[FullHttpResponse]
}

class DefaultHttpClient(private val bootstrap: Bootstrap)(host: String, port: Int) extends HttpClient with LogSupport {

//  import base.NettySugar.syntax._
  import scala.collection.JavaConverters._

  type Request = HttpReq
  type Response = FullHttpResponse


  private val clientStatus: AtomicReference[ClientStatus] = new AtomicReference(ClientStatus.Disconnected)
  @volatile private var connectionChannel: Option[ChannelFuture] = None

  private val taskQueue = new PriorityBlockingQueue[Task](20, (t1: Task, t2: Task) =>
    if (t1.priority == t2.priority) 0
    else if (t1.priority < t2.priority) -1
    else 1)

  private val activeRequest: AtomicReference[Option[RequestHandler]] = new AtomicReference(None)

  protected val responseHandler = new HttpClientResponseHandler()

  private val _boot = bootstrap
    .handler(new ChannelInitializer[SocketChannel]() {
      def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        p.addLast(new HttpClientCodec())
        p.addLast(new HttpObjectAggregator(10000))
        p.addLast(responseHandler)
      }
    })

  def addTask(task: Task): Unit = {
    taskQueue.add(task)
    tryPollTask()
  }

  def tryPollTask(): Unit = {

    val validStatus = List(ClientStatus.Disconnected, ClientStatus.Idle)
    validStatus
      .find(validStatusFrom => clientStatus.compareAndSet(validStatusFrom, ClientStatus.Busy))
      .foreach(statusFrom => {
        val task = taskQueue.poll()
        if (task != null) {
          log.debug(s"Starting task: $task")
          task.process(statusFrom,
            newStatus => {
              val result = clientStatus.compareAndSet(ClientStatus.Busy, newStatus)
              if (!result)
                log.warn(s"Failed trying to change connection status from ${ClientStatus.Busy} to $newStatus. Current status: ${clientStatus.get()}. Some potential bug is happening?")
              tryPollTask()
            })
        }
      })
  }

  def connection: ChannelFuture = synchronized {
    connectionChannel.getOrElse {
      val channelFuture = this.connect()
      connectionChannel = Some(channelFuture)
      channelFuture
    }
  }

  protected def connect(): ChannelFuture = {
    log.debug(s"Establishing connection to $host:$port")
    val channel = _boot.connect(host, port)
    channel.channel().closeFuture().addListener((_: ChannelFuture) => {
      log.debug(s"Connection has been closed.")
      connectionChannel = None
      close()
    })
    // need to force initialization
    channel.channel().asInstanceOf[ChannelContextAware].context
    channel
  }

  def get(uri: String): NFuture[FullHttpResponse] = {
    val request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri)
    HttpHeaders.setContentLength(request, 0)
    this.addRequest(HttpReq(Seq(request)))
  }

  def post(uri: String, content: Option[ByteBuf]): NFuture[FullHttpResponse] = {
    val request = content match {
      case Some(c) => new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, c)
      case None => new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri)
    }
    HttpHeaders.setContentLength(request, content.map(_.readableBytes()).getOrElse(0).toLong)
    this.addRequest(HttpReq(Seq(request)))
  }

  def addRequest(request: HttpRequest): NFuture[this.Response] = {
    this.addRequest(HttpReq(Seq(request)))
  }

  def addRequest(request: this.Request): NFuture[this.Response] = {
    val result: Promise[this.Response] = ImmediateEventExecutor.INSTANCE.newPromise.asInstanceOf[Promise[this.Response]]
    val task = RequestTask(RequestHandler(request, result))
    addTask(task)
    result
  }

  def close(): NFuture[Unit] = {
    val promise: Promise[Unit] = ImmediateEventExecutor.INSTANCE.newPromise.asInstanceOf[Promise[Unit]]
    val task = CloseConnectionTask(CloseHandler(promise))
    addTask(task)
    promise
  }

  case class RequestHandler(request: Request, promise: Promise[Response])
  case class CloseHandler(promise: Promise[Unit])

  trait Task {
    def priority: Int
    def statusFrom: Seq[ClientStatus]

    def process(statusFrom: ClientStatus,
                finishF: ClientStatus => Unit): Unit
  }

  case class RequestTask(request: RequestHandler) extends Task {
    val priority: Int = 10

    override def statusFrom: Seq[ClientStatus] = Seq(ClientStatus.Idle, ClientStatus.Disconnected)

    override def process(statusFrom: ClientStatus,
                         finishF: ClientStatus => Unit): Unit = {
      connection.addListener((f: ChannelFuture) => {
        if (f.isSuccess) {
          activeRequest.set(Some(request))
          val arh = ActiveRequestHandler(
            res => request.promise.setSuccess(res.asInstanceOf[FullHttpResponse]),
            () => finishF(ClientStatus.Idle),
            _ => {
              close()
              finishF(ClientStatus.Idle)
            }
          )
          responseHandler.subscribeHandler(arh)
          executeRequest(f.channel())(request.request)
        } else {
          if (request.promise.isCancellable) request.promise.cancel(false)
          finishF(statusFrom)
        }
      })
    }

    private def executeRequest(channel: Channel)(request: Request): Unit = {
      log.debug(s"-----------------> Processing request: ${request.content.headOption}")
      request.content.foreach(channel.write)
      channel.flush()
    }
  }

  case class CancelRequestTask() extends Task {
    val priority: Int = 5

    override def statusFrom: Seq[ClientStatus] = Seq(ClientStatus.Idle, ClientStatus.Disconnected)

    override def process(statusFrom: ClientStatus,
                         finishF: ClientStatus => Unit): Unit = {
      val drained = new util.ArrayList[Task]()
      val totalTasks = taskQueue.drainTo(drained)
      var countRequests = 0
      drained.asScala.foreach {
        case RequestTask(reqHandler) =>
          countRequests += 1
          if (reqHandler.promise.isCancellable) reqHandler.promise.cancel(false)
        case x: Task => taskQueue.add(x)
      }
      log.debug(s"Draining $countRequests enqueued requests from $totalTasks tasks due to connection has closed")
      finishF(statusFrom)
    }
  }

  case class CloseConnectionTask(closeHandler: CloseHandler) extends Task {
    val priority: Int = 1

    override def statusFrom: Seq[ClientStatus] = Seq(ClientStatus.Idle, ClientStatus.Disconnected)

    override def process(statusFrom: ClientStatus,
                         finishF: ClientStatus => Unit): Unit = {
      connectionChannel match {
        case Some(cf) => cf.channel().close().addListener((_: ChannelFuture) => {
          afterConnectionClose(finishF)
        })
        case None =>
          afterConnectionClose(finishF)
      }
    }

    private def afterConnectionClose(finishF: (ClientStatus) => Unit): Unit = {
      addTask(CancelRequestTask())
      closeHandler.promise.setSuccess(())
      finishF(ClientStatus.Disconnected)
    }
  }

  trait ClientStatus
  object ClientStatus {
    case object Disconnected extends ClientStatus
    case object Idle extends ClientStatus
    case object Busy extends ClientStatus
  }

  case class ActiveRequestHandler(result: HttpObject => Unit, success: () => Unit, fail: Throwable => Unit)

  class HttpClientResponseHandler() extends SimpleChannelInboundHandler[HttpObject] with LogSupport {

    @volatile private var activeRequestHandler: Option[ActiveRequestHandler] = None

    def subscribeHandler(handler: ActiveRequestHandler): Unit = activeRequestHandler = Some(handler)

    def onResponseArriveEvent(msg: HttpObject): Unit = activeRequestHandler.foreach(_.result(msg))

    def onResponseCompletedEvent(): Unit = activeRequestHandler.foreach(_.success())

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
      activeRequestHandler.foreach(_.fail(cause))
    }
  }

  case class HttpReq(content: Seq[AnyRef], keepAlive: Boolean = true)
}

object DefaultHttpClient {

  def withNio(workerGroup: NioEventLoopGroup)(host: String, port: Int): DefaultHttpClient = {
    val boot = new Bootstrap()
    boot.group(workerGroup)
      .channel(classOf[NioSocketChannel])
      .option[lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

    new DefaultHttpClient(boot)(host, port)
  }

  def withEpoll(workerGroup: EpollEventLoopGroup)(host: String, port: Int): DefaultHttpClient = {
    val boot = new Bootstrap()
    boot.group(workerGroup)
      .channel(classOf[EpollSocketChannel])
      .option[lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

    new DefaultHttpClient(boot)(host, port)
  }
}
