package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.server.Directives.{as, complete, entity, optionalHeaderValueByName, path, post, _}
import io.hydrosphere.serving.gateway.service.{ApplicationExecutionService, JsonServeByIdRequest, JsonServeByNameRequest, RequestTracingInfo}
import io.hydrosphere.serving.http.TracingHeaders
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

class JsonPredictionController(
  gatewayPredictionService: ApplicationExecutionService
) extends JsonProtocols with Logging {

  def compatibleServeById = path("api" / "v1" / "applications" / "serve" / LongNumber / Segment) { (appId, signatureName) =>
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
          }
        }
      }
    }
  }

  def listApps = pathPrefix("applications") {
    get {
      complete(gatewayPredictionService.listApps)
    }
  }

  def serveById = pathPrefix("applications" / "serveById" / LongNumber / Segment) { (appId, signatureName) =>
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
          }
        }
      }
    }
  }

  def serveByName = pathPrefix("applications" / "serveByName" / Segment / Segment) { (appName, signatureName) =>
    post {
      //TODO simplify optionalHeaderValueByName
      optionalHeaderValueByName(TracingHeaders.xRequestId) {
        reqId => {
          optionalHeaderValueByName(TracingHeaders.xB3TraceId) {
            reqB3Id => {
              optionalHeaderValueByName(TracingHeaders.xB3SpanId) {
                reqB3SpanId => {
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
          }
        }
      }
    }
  }

  def v1 = pathPrefix("gateway" / "v1") {
    listApps ~ serveById ~ serveByName
  }

  val routes = compatibleServeById ~ v1

}
