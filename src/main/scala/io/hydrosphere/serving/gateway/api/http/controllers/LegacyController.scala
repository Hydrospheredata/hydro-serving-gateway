package io.hydrosphere.serving.gateway.api.http.controllers

import cats.data.OptionT
import cats.effect.Effect
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import spray.json.JsObject

class LegacyController[F[_]](
  executor: ExecutionService[F],
  appStorage: ApplicationStorage[F]
)(implicit F: Effect[F]) extends GenericController {

  def compatibleServeById = path("api" / "v1" / "applications" / "serve" / LongNumber / Segment) { (appId, _) =>
    post {
      entity(as[JsObject]) { bytes =>
        completeF {
          logger.info(s"Serve JSON (v1) request: id=$appId")
          for {
            app <- OptionT(appStorage.getById(appId)).getOrElseF(
              F.raiseError(GatewayError.NotFound(s"Can't find application with id $appId"))
            )
            x <- jsonToRequest(app.name, bytes, app.signature)
            res <- executor.predict(x)
          } yield responseToJsObject(res)
        }
      }
    }
  }

  def routes = compatibleServeById
}