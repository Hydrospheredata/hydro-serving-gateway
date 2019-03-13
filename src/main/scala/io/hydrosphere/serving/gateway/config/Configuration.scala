package io.hydrosphere.serving.gateway.config

import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration.Duration

final case class ManagerConfig(
  host: String,
  port: Int,
  xdsSilentRestartSeconds: Long
)

final case class MonitoringConfig(
  host: String,
  port: Int
)

final case class GrpcConfig(
  port: Int,
  deadline: Duration,
  maxMessageSize: Int = 4 * 1024 * 1024,
)

final case class HttpConfig(
  port: Int
)

final case class ReqStoreConfig(
  enabled: Boolean,
  host: String,
  port: Int,
  schema: String
)

final case class ApplicationConfig(
  grpc: GrpcConfig,
  manager: ManagerConfig,
  http: HttpConfig,
  shadowingOn: Boolean,
  monitoring: MonitoringConfig,
  reqstore: ReqStoreConfig
)

final case class Configuration(
  application: ApplicationConfig,
)


object Configuration extends Logging {
  def loadOrFail() = {
    val loadResult = pureconfig.loadConfig[Configuration]
    loadResult match {
      case Left(error) =>
        logger.error(s"Can't load configuration: $error")
        throw new IllegalArgumentException(error.toList.map(_.description).mkString("\n"))
      case Right(value) =>
        value
    }
  }
}