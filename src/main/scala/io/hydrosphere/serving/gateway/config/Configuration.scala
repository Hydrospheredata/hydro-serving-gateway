package io.hydrosphere.serving.gateway.config

import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration.Duration

final case class SidecarConfig(
  host: String,
  port: Int,
  xdsSilentRestartSeconds: Long
)

final case class GrpcConfig(
  port: Int,
  deadline: Duration,
  maxMessageSize: Int = 4 * 1024 * 1024,
)

final case class HttpConfig(
  port: Int
)

sealed trait HttpServiceAddr
object HttpServiceAddr {
  final case class EnvoyRoute(name: String) extends HttpServiceAddr
  final case class RealAddress(
    host: String,
    port: Int,
    schema: String
  ) extends HttpServiceAddr
}

final case class ReqStoreConfig(
  enabled: Boolean,
  address: HttpServiceAddr
)

final case class ApplicationConfig(
  grpc: GrpcConfig,
  http: HttpConfig,
  shadowingOn: Boolean,
  profilingDestination: String,
  monitoringDestination: String,
  reqstore: ReqStoreConfig
)

final case class Configuration(
  application: ApplicationConfig,
  sidecar: SidecarConfig,
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