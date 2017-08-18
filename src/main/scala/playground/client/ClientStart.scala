package playground.client

import base.{Config, ConfigSupport, LogSupport}
import kamon.Kamon
import kamon.jaeger.Jaeger

object ClientStart extends App with ConfigSupport with LogSupport {

  private val generatorConfig: Config.RequestGenerator = config.requestGenerator

  log.info("Starting client...")

  Kamon.addReporter(new Jaeger)

  ABSimulator(generatorConfig.count, generatorConfig.parallel)
    .start(generatorConfig.host, generatorConfig.port)

}
