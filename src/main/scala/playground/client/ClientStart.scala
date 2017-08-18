package playground.client

import base.LogSupport

object ClientStart extends App with LogSupport {

  log.info("Starting client...")

  HttpClient(count = 5, parallel = 2).start()

}
