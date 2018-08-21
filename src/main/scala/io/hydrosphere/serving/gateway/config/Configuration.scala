package io.hydrosphere.serving.gateway.config

import org.apache.logging.log4j.scala.Logging

case class SidecarConfig(
  host: String,
  port: Int
)

case class ApplicationConfig(
  grpcPort: Int,
  httpPort: Int,
  shadowingOn: Boolean,
  shadowingDestination: String
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