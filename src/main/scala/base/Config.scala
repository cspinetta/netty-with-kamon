package base

import com.typesafe.config.{Config => TSConfig}
import com.typesafe.config.ConfigFactory

trait ConfigSupport {
  val config: Config.type = Config
}

sealed trait Config

object Config {

  private val config: TSConfig = ConfigFactory.load()

  val requestGenerator: RequestGenerator = pureconfig.loadConfigOrThrow[RequestGenerator](config.getConfig("request-generator"))

  case class RequestGenerator(host: String, port: Int, count: Int, parallel: Int, requests: List[CandidateRequest], kamonEnabled: Boolean) extends Config
  case class CandidateRequest(uri: String, method: String, content: Option[String]) extends Config
}

