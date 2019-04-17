package io.hydrosphere.serving.gateway.api.http.controllers

import akka.http.scaladsl.server.Directives.{as, complete, entity, path, post, _}
import cats.effect.Effect
import cats.effect.syntax.effect._
import io.hydrosphere.serving.gateway.service.application.{ApplicationExecutionService, RequestTracingInfo}
import io.hydrosphere.serving.http.TracingHeaders
import spray.json.JsObject

class LegacyController[F[_]: Effect](
  applicationExecutionService: ApplicationExecutionService[F]
) extends GenericController {

  def compatibleServeById = path("api" / "v1" / "applications" / "serve" / LongNumber / Segment) { (appId, _) =>
    post {
      optionalTracingHeaders { (reqId, reqB3Id, reqB3SpanId) =>
        entity(as[JsObject]) { bytes =>
          complete {
            logger.info(s"Serve JSON (v1) request: id=$appId")
            val request = JsonServeByIdRequest(
              targetId = appId,
              inputs = bytes
            )
            applicationExecutionService.serveJsonById(request).toIO.unsafeToFuture()
          }
        }
      }
    }
  }

  def routes = compatibleServeById
}
