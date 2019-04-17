package io.hydrosphere.serving.gateway.api.http.controllers

import akka.http.scaladsl.server.Directives._
import cats.effect.Effect
import cats.effect.syntax.effect._
import io.hydrosphere.serving.gateway.service.application.{ApplicationExecutionService, RequestTracingInfo}
import spray.json.JsObject

class ServableController[F[_]: Effect](
  applicationExecutionService: ApplicationExecutionService[F]
) extends GenericController {

  def listServables =
    pathEndOrSingleSlash {
      get {
        complete(applicationExecutionService.listApps.toIO.unsafeToFuture())
      }
    }


  def serveServable = pathPrefix(Segment / Segment) { (modelName, modelVersion) =>
    post {
      optionalTracingHeaders { (reqId, reqB3Id, reqB3SpanId) =>
        entity(as[JsObject]) { jsObject =>
          complete {
            logger.info(s"Servable serve request: name=$modelName version=$modelVersion")
            val request = JsonServeByNameRequest(
              appName = appName,
              inputs = jsObject
            )
            val maybeTracingInfo = reqId.map(xRequestId =>
              RequestTracingInfo(
                xRequestId = xRequestId,
                xB3requestId = reqB3Id,
                xB3SpanId = reqB3SpanId
              )
            )
            applicationExecutionService.serveJsonByName(request, maybeTracingInfo).toIO.unsafeToFuture()
          }
        }
      }
    }
  }

  val routes = pathPrefix("servable") {
    listServables ~ serveServable
  }

}
