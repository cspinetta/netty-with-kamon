package playground.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.epoll.{EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelInitializer, ChannelOption, EventLoopGroup}
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.util.internal.logging.{InternalLoggerFactory, Slf4JLoggerFactory}


object HttpServer {

  def start(port: Int): Unit = {
    InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory())

    val bossGroup: EventLoopGroup = new EpollEventLoopGroup(1)
    val workerGroup: EventLoopGroup = new EpollEventLoopGroup(1)

    try {
      val b = new ServerBootstrap()
      b.option(ChannelOption.SO_BACKLOG, Int.box(1024))
        .group(bossGroup, workerGroup)
        .channel(classOf[EpollServerSocketChannel])
        .childHandler(new HttpHelloWorldServerInitializer())

      val ch = b.bind(port).sync().channel()
      ch.closeFuture().sync()
    } finally {
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()
    }
  }
}

class HttpHelloWorldServerInitializer extends ChannelInitializer[SocketChannel] {

  def initChannel(ch: SocketChannel): Unit = {
    val p = ch.pipeline()

    //    p.addLast("logger", new LoggingHandler())
    p.addLast("codec", new HttpServerCodec())
    p.addLast("handler", new HttpServerHandler())
  }
}
