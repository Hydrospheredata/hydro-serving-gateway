package io.hydrosphere.serving.gateway.api.http.controllers

import cats.data.OptionT
import cats.effect.Effect
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import spray.json.JsObject

class ApplicationController[F[_]](
  appStorage: ApplicationStorage[F],
  executor: ExecutionService[F]
)(implicit F: Effect[F]) extends GenericController {

  def listApps = pathEndOrSingleSlash {
    get {
      completeF(appStorage.listAll)
    }
  }

  def serveByName = pathPrefix(Segment) { appName =>
    post {
      entity(as[JsObject]) { jsObject =>
        completeF {
          logger.info(s"Serve request: name=$appName")
          for {
            app <- OptionT(appStorage.getByName(appName))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find application with name $appName")))
            req <- jsonToRequest(app.name, None, jsObject, app.signature)
            res <- executor.predict(req)
          } yield responseToJsObject(res)
        }
      }
    }
  }

  val routes = pathPrefix("application") {
    listApps ~ serveByName
  }
}
