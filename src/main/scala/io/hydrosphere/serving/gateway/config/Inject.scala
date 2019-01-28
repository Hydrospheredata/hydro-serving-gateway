package io.hydrosphere.serving.gateway.config

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.apache.logging.log4j.scala.Logging

object Inject extends Logging {
  logger.info("Reading configuration")
  implicit val appConfig: Configuration = Configuration.loadOrFail()
  logger.info(s"Configuration: $appConfig")

  implicit val actorSystem = ActorSystem("hydroserving-gateway")
  implicit val actorMat = ActorMaterializer.create(actorSystem)
}