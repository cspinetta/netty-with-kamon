package playground
package server

import base.LogSupport
import kamon.Kamon
import kamon.jaeger.Jaeger

object ServerStart extends App with LogSupport {
  val port = args.headOption.map(_.toInt).getOrElse(8080)

  log.info(s"Starting server at http://localhost:$port")

  Kamon.addReporter(new Jaeger)
  
  HttpServer.start(port)
}
