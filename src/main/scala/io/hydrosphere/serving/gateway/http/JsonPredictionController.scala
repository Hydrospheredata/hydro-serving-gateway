package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.server.Directives.{as, complete, entity, optionalHeaderValueByName, path, post, _}
import akka.util.Timeout
import io.hydrosphere.serving.gateway.service.{ApplicationExecutionService, JsonServeRequest, RequestTracingInfo}
import io.hydrosphere.serving.http.TracingHeaders
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

import scala.concurrent.duration._

class JsonPredictionController(
  gatewayPredictionService: ApplicationExecutionService
) extends JsonProtocols with Logging {

  def serveById = path("api" / "v1" / "applications" / "serve" / LongNumber / Segment) { (appId, signatureName) =>
    post {
      //TODO simplify optionalHeaderValueByName
      optionalHeaderValueByName(TracingHeaders.xRequestId) {
        reqId => {
          optionalHeaderValueByName(TracingHeaders.xB3TraceId) {
            reqB3Id => {
              optionalHeaderValueByName(TracingHeaders.xB3SpanId) {
                reqB3SpanId => {
                  entity(as[JsObject]) { bytes =>
                    complete {
                      logger.info(s"Serve request: id=$appId signature=$signatureName")
                      gatewayPredictionService.serveJsonApplication(
                        JsonServeRequest(
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
          }
        }
      }
    }
  }

  def serveByName = path("api" / "v1" / "serve" / Segment / Segment) { (appName, signatureName) =>
    ???
  }


  val routes = serveById
}
