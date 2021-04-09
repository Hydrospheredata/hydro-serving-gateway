package io.hydrosphere.serving.gateway.api.http.controllers

import cats.data.OptionT
import cats.effect.Effect
import cats.implicits._
import io.circe._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._

class ApplicationController[F[_]](
  appStorage: ApplicationStorage[F],
  executor: ExecutionService[F]
)(implicit F: Effect[F]) extends GenericController {

  def serveByName = pathPrefix(Segment) { appName =>
    post {
      entity(as[Json]) { json =>
        completeF {
          logger.info(s"Serve application request, name=$appName")
          for {
            app <- OptionT(appStorage.getByName(appName))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find application with the name $appName")))
            req <- jsonToRequest(app.name, json, app.signature)
            res <- executor.predictApplication(req)
          } yield responseToJsObject(res)
        }
      }
    }
  }

  val routes = pathPrefix("application") {
    serveByName
  }
}
