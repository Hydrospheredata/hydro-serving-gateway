package io.hydrosphere.serving.gateway.api.http.controllers

import akka.http.scaladsl.server.Directives._
import cats.effect.Effect
import cats.effect.syntax.effect._
import io.hydrosphere.serving.gateway.service.application.{ApplicationExecutionService, RequestTracingInfo}
import spray.json.JsObject

class ApplicationController[F[_]: Effect](
  applicationExecutionService: ApplicationExecutionService[F]
) extends GenericController {

  def listApps = pathEndOrSingleSlash {
      get {
        complete(applicationExecutionService.listApps.toIO.unsafeToFuture())
      }
    }

  def serveByName = pathPrefix( Segment) { appName =>
    post {
      optionalTracingHeaders { (reqId, reqB3Id, reqB3SpanId) =>
        entity(as[JsObject]) { jsObject =>
          complete {
            logger.info(s"Serve request: name=$appName")
            val request = JsonServeByNameRequest(
              appName = appName,
              inputs = jsObject
            )
            applicationExecutionService.serveJsonByName(request).toIO.unsafeToFuture()
          }
        }
      }
    }
  }

  val routes = pathPrefix("application") {
    listApps ~ serveByName
  }

}
