package io.hydrosphere.serving.gateway.api.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.stream.ActorMaterializer
import cats.effect.Effect
import cats.implicits._
import ch.megard.akka.http.cors.scaladsl.CorsDirectives
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import io.hydrosphere.serving.gateway.{BuildInfo, Contract, Logging}
import io.hydrosphere.serving.gateway.GatewayError.{InternalError, InvalidArgument, InvalidPredictMessage, NotFound}
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.api.http.controllers.{ApplicationController, ServableController}
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import io.hydrosphere.serving.gateway.util.AsyncUtil
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext

class HttpApi[F[_]: Effect](
  configuration: ApplicationConfig,
  executionService: ExecutionService[F],
  appStorage: ApplicationStorage[F],
  servableStorage: ServableStorage[F]
)(
  implicit val system: ActorSystem,
  implicit val materializer: ActorMaterializer
) extends Logging {

  val commonExceptionHandler = ExceptionHandler {
    case InternalError(msg) =>
      logger.error(msg)
      complete(
        StatusCodes.InternalServerError -> Map(
          "error" -> "InternalServerError",
          "information" -> msg
        )
      )
    case NotFound(msg) =>
      complete(
        StatusCodes.NotFound -> Map(
          "error" -> "NotFound",
          "information" -> msg
        )
      )
    case InvalidArgument(msg) =>
      complete(
        StatusCodes.BadRequest -> Map(
          "error" -> "BadRequest",
          "information" -> msg
        )
      )
    case InvalidPredictMessage(fields, msg) =>
      complete(
        StatusCodes.BadRequest -> Map(
          "error" -> msg,
          "information" -> fields.toString
        )
      )
    case x: Contract.ContractViolationError =>
      complete(
        StatusCodes.BadRequest -> Map(
          "error" -> "ContractViolationError",
          "information" -> x.getMessage
        )
      )
    case p: Throwable =>
      logger.error(p.getMessage, p)
      complete(
        StatusCodes.InternalServerError -> Map(
          "error" -> "InternalUncaught",
          "information" -> Option(p.getMessage).getOrElse("Unknown error (exception message == null)")
        )
      )
  }

  val applicationController = new ApplicationController(appStorage, executionService)
  val servableController = new ServableController(servableStorage, executionService)

  val routes: Route = CorsDirectives.cors(CorsSettings.defaultSettings.withAllowedMethods(Seq(GET, POST, HEAD, OPTIONS, PUT, DELETE))) {
    handleExceptions(commonExceptionHandler) {
      pathPrefix("gateway") {
        applicationController.routes ~
          servableController.routes ~
          path("buildinfo") {
            complete(HttpResponse(
              status = StatusCodes.OK,
              entity = HttpEntity(ContentTypes.`application/json`, BuildInfo.toJson)
            ))
          }
      } ~
        path("health") {
          complete {
            "OK"
          }
        }
    }
  }

  def start()(implicit ec: ExecutionContext) = {
    for {
      s <- AsyncUtil.futureAsync(Http().bindAndHandle(routes, "0.0.0.0", configuration.http.port))
      _ <- Logging.info[F](s"Started HTTP API server @ 0.0.0.0:${configuration.http.port}")
    } yield s
  }
}