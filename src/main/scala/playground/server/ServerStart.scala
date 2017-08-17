package playground
package server

object ServerStart extends App {
  val port = args.headOption.map(_.toInt).getOrElse(8080)

  println(s"Starting server on port $port")

  HttpServer.start(port)
}
