package io.hydrosphere.serving.gateway.config

import com.typesafe.config.Config
import org.apache.logging.log4j.scala.Logging

case class SidecarConfig(
  host: String,
  port: Int
)

case class ApplicationConfig(
  port: Int,
  httpPort: Int,
  shadowingOn: Boolean,
  shadowingDestination: String
)

final case class Configuration(
  application: ApplicationConfig,
  sidecarConfig: SidecarConfig,
)

object Configuration extends Logging {

  private[Configuration] def logged[T](configName: String)(wrapped: T): T = {
    logger.info(s"configuration: $configName: $wrapped")
    wrapped
  }

  def parseApplication(config: Config): ApplicationConfig = logged("base app") {
    val c = config.getConfig("application")
    ApplicationConfig(
      port = c.getInt("port"),
      httpPort = c.getInt("httpPort"),
      shadowingOn = c.getBoolean("shadowingOn"),
      shadowingDestination = c.getString("shadowingDestination")
    )
  }

  def parseSidecar(config: Config): SidecarConfig = logged("sidecar") {
    val c = config.getConfig("sidecar")
    SidecarConfig(
      host = c.getString("host"),
      port = c.getInt("port")
    )
  }

  def apply(config: Config): Configuration = {
    logger.info(config)
    Configuration(
      parseApplication(config),
      parseSidecar(config)
    )
  }
}

