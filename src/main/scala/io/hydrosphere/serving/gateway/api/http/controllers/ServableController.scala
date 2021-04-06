package io.hydrosphere.serving.gateway.api.http.controllers

import cats.data.OptionT
import cats.effect.Effect
import cats.implicits._
import io.circe._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._

class ServableController[F[_]](
  servableStorage: ServableStorage[F],
  executor: ExecutionService[F]
)(implicit F: Effect[F]) extends GenericController {

  def serveServable = pathPrefix(Segment) { servableName =>
    post {
      entity(as[Json]) { json =>
        completeF {
          logger.info(s"Servable serve request: name=$servableName")
          for {
            servable <- OptionT(servableStorage.get(servableName))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find a servable instance with name $servableName")))
            req <- jsonToRequest(servableName, json, servable.modelVersion.signature)
            res <- executor.predictServable(req)
          } yield responseToJsObject(res)
        }
      }
    }
  }

  val routes = pathPrefix("servable") {
    serveServable
  }
}