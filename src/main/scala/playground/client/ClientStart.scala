package playground.client

import base.LogSupport

object ClientStart extends App with LogSupport {

  log.info("Starting client...")

  ABSimulator(count = 100, parallel = 5).start()

}
