package io.hydrosphere.serving.gateway.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.config.Inject.logger
import io.hydrosphere.serving.gateway.service.ApplicationExecutionService
import org.apache.logging.log4j.scala.Logging
import spray.json._

import scala.collection.immutable.Seq

class HttpApi(
  configuration: ApplicationConfig,
  gatewayPredictionService: ApplicationExecutionService
)(
  implicit val system: ActorSystem,
  implicit val materializer: ActorMaterializer
) extends Logging with JsonProtocols {

  val commonExceptionHandler = ExceptionHandler {
    case p: Throwable =>
      logger.error(p.getMessage, p)
      complete(
        StatusCodes.InternalServerError -> Map(
          "error" -> "InternalUncaught",
          "information" -> Option(p.getMessage).getOrElse("Unknown error (exception message == null)")
        )
      )
  }

  val predictionController = new JsonPredictionController(gatewayPredictionService)

  val routes: Route = CorsDirectives.cors(
    CorsSettings.defaultSettings.copy(allowedMethods = Seq(GET, POST, HEAD, OPTIONS, PUT, DELETE))
  ) {
    handleExceptions(commonExceptionHandler) {
      predictionController.routes ~
        path("health") {
          complete {
            "OK"
          }
        }
    }
  }

  logger.info(s"Starting HTTP API server @ 0.0.0.0:${configuration.http.port}")
  val serverBinding = Http().bindAndHandle(routes, "0.0.0.0", configuration.http.port)
}