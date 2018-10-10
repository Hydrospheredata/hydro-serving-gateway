package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.server.Directives._
import io.hydrosphere.serving.gateway.service.{ApplicationExecutionService, JsonServeByIdRequest, JsonServeByNameRequest, RequestTracingInfo}
import io.hydrosphere.serving.http.TracingHeaders
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

class JsonPredictionController(
  gatewayPredictionService: ApplicationExecutionService
) extends JsonProtocols with Logging {

  def optionalTracingHeaders = optionalHeaderValueByName(TracingHeaders.xRequestId) &
    optionalHeaderValueByName(TracingHeaders.xB3TraceId) &
    optionalHeaderValueByName(TracingHeaders.xB3SpanId)

  def compatibleServeById = path("api" / "v1" / "applications" / "serve" / LongNumber / Segment) { (appId, signatureName) =>
    post {
      optionalTracingHeaders { (reqId, reqB3Id, reqB3SpanId) =>
        entity(as[JsObject]) { bytes =>
          complete {
            logger.info(s"Serve request: id=$appId signature=$signatureName")
            gatewayPredictionService.serveJsonById(
              JsonServeByIdRequest(
                targetId = appId,
                signatureName = signatureName,
                inputs = bytes
              ),
              reqId.map(xRequestId =>
                RequestTracingInfo(
                  xRequestId = xRequestId,
                  xB3requestId = reqB3Id,
                  xB3SpanId = reqB3SpanId
                )
              )
            )
          }
        }
      }
    }
  }

  def listApps = pathPrefix("applications") {
    pathEndOrSingleSlash {
      get {
        complete(gatewayPredictionService.listApps)
      }
    }
  }

  def serveByName = pathPrefix("applications" / Segment / Segment) { (appName, signatureName) =>
    post {
      optionalTracingHeaders { (reqId, reqB3Id, reqB3SpanId) =>
        entity(as[JsObject]) { jsObject =>
          complete {
            logger.info(s"Serve request: name=$appName signature=$signatureName")
            gatewayPredictionService.serveJsonByName(
              JsonServeByNameRequest(
                appName = appName,
                signatureName = signatureName,
                inputs = jsObject
              ),
              reqId.map(xRequestId =>
                RequestTracingInfo(
                  xRequestId = xRequestId,
                  xB3requestId = reqB3Id,
                  xB3SpanId = reqB3SpanId
                )
              )
            )
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
