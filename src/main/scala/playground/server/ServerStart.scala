package playground
package server

import kamon.Kamon
import kamon.jaeger.Jaeger

object ServerStart extends App {
  val port = args.headOption.map(_.toInt).getOrElse(8080)

  println(s"Starting server on port $port")

  Kamon.addReporter(new Jaeger)
  
  HttpServer.start(port)
}
