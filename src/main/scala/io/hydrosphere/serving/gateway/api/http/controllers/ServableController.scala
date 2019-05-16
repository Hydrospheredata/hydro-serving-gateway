package io.hydrosphere.serving.gateway.api.http.controllers

import cats.data.OptionT
import cats.effect.Effect
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import spray.json.JsObject

class ServableController[F[_]](
  servableStorage: ServableStorage[F],
  executor: ExecutionService[F]
)(implicit F: Effect[F]) extends GenericController {

  def listServables = pathEndOrSingleSlash {
    get {
      completeF(servableStorage.list)
    }
  }


  def serveServable = pathPrefix(Segment / LongNumber) { (modelName, modelVersion) =>
    post {
      entity(as[JsObject]) { jsObject =>
        completeF {
          logger.info(s"Servable serve request: name=$modelName version=$modelVersion")
          for {
            servable <- OptionT(servableStorage.getByModelVersion(modelName, modelVersion))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find servable for a model ${modelName}:${modelVersion}")))
            req <- jsonToRequest(modelName, Some(modelVersion), jsObject, servable.modelVersion.predict)
            res <- executor.predict(req)
          } yield responseToJsObject(res)
        }
      }
    }
  }

  val routes = pathPrefix("servable") {
    listServables ~ serveServable
  }
}