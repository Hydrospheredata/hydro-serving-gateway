package io.hydrosphere.serving.gateway.config

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.HttpOriginRange
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.model.HttpHeaderRange
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import org.apache.logging.log4j.scala.Logging

import scala.collection.immutable.Seq

object Inject extends Logging {
  logger.info("Reading configuration")
  implicit val appConfig: Configuration = Configuration.loadOrFail()
  logger.info(s"Configuration: $appConfig")

  implicit val actorSystem = ActorSystem("hydroserving-gateway")
  implicit val actorMat = ActorMaterializer.create(actorSystem)

  implicit val corsSettings: CorsSettings.Default = CorsSettings.Default(
    allowGenericHttpRequests = true,
    allowCredentials = true,
    allowedOrigins = HttpOriginRange.*,
    allowedHeaders = HttpHeaderRange.*,
    allowedMethods = Seq(GET, POST, HEAD, OPTIONS, DELETE),
    exposedHeaders = Seq.empty,
    maxAge = Some(30 * 60)
  )
}