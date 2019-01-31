package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.server.Directives._
import cats.effect.Effect
import cats.effect.syntax.effect._
import io.hydrosphere.serving.gateway.service.application.{ApplicationExecutionService, JsonServeByIdRequest, JsonServeByNameRequest, RequestTracingInfo}
import io.hydrosphere.serving.http.TracingHeaders
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

class JsonPredictionController[F[_]: Effect](
  applicationExecutionService: ApplicationExecutionService[F]
) extends JsonProtocols with Logging {

  def optionalTracingHeaders = optionalHeaderValueByName(TracingHeaders.xRequestId) &
    optionalHeaderValueByName(TracingHeaders.xB3TraceId) &
    optionalHeaderValueByName(TracingHeaders.xB3SpanId)

  def compatibleServeById = path("api" / "v1" / "applications" / "serve" / LongNumber / Segment) { (appId, _) =>
    post {
      optionalTracingHeaders { (reqId, reqB3Id, reqB3SpanId) =>
        entity(as[JsObject]) { bytes =>
          complete {
            logger.info(s"Serve request: id=$appId")
            val request = JsonServeByIdRequest(
              targetId = appId,
              inputs = bytes
            )
            val maybeTracingInfo = reqId.map(xRequestId =>
              RequestTracingInfo(
                xRequestId = xRequestId,
                xB3requestId = reqB3Id,
                xB3SpanId = reqB3SpanId
              )
            )
            applicationExecutionService.serveJsonById(request, maybeTracingInfo).toIO.unsafeToFuture()
          }
        }
      }
    }
  }

  def listApps = pathPrefix("application") {
    pathEndOrSingleSlash {
      get {
        complete(applicationExecutionService.listApps.toIO.unsafeToFuture())
      }
    }
  }

  def serveByName = pathPrefix("application" / Segment) { (appName) =>
    post {
      optionalTracingHeaders { (reqId, reqB3Id, reqB3SpanId) =>
        entity(as[JsObject]) { jsObject =>
          complete {
            logger.info(s"Serve request: name=$appName")
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

  def appRoutes = pathPrefix("gateway") {
    listApps ~ serveByName
  }

  val routes = compatibleServeById ~ appRoutes

}
