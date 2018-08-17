package io.hydrosphere.serving.gateway.controller

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives.{as, complete, entity, optionalHeaderValueByName, path, post}
import akka.util.Timeout
import io.hydrosphere.serving.gateway.service.{GatewayPredictionService, JsonServeRequest, RequestTracingInfo}
import spray.json.{DefaultJsonProtocol, JsArray, RootJsonFormat}
import akka.http.scaladsl.server.Directives._
import io.hydrosphere.serving.http.TracingHeaders
import io.hydrosphere.serving.model.api.Result.{ClientError, ErrorCollection, HError, InternalError}
import spray.json.{JsObject, JsString, JsValue, _}

import scala.concurrent.duration._

class JsonPredictionController(
  gatewayPredictionService: GatewayPredictionService
) extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val timeout = Timeout(5.minutes)

  implicit def internalErrorFormat[T <: Throwable] = new RootJsonFormat[InternalError[T]] {
    override def write(obj: InternalError[T]): JsValue = {
      val fields = Map(
        "exception" -> JsString(obj.exception.getMessage)
      )
      val reasonField = obj.reason.map { r =>
        Map("reason" -> JsString(r))
      }.getOrElse(Map.empty)

      JsObject(fields ++ reasonField)
    }

    override def read(json: JsValue): InternalError[T] = ???
  }

  implicit val clientErrorFormat = jsonFormat1(ClientError.apply)

  implicit val errorFormat = new RootJsonFormat[HError] {
    override def write(obj: HError): JsValue = {
      obj match {
        case x: ClientError => JsObject(Map(
          "error" -> JsString("Client"),
          "information" -> x.toJson
        ))
        case x: InternalError[_] => JsObject(Map(
          "error" -> JsString("Internal"),
          "information" -> x.toJson
        ))
        case ErrorCollection(errors) => JsObject(Map(
          "error" -> JsString("Multiple"),
          "information" -> JsArray(errors.map(write).toVector)
        ))
      }
    }

    override def read(json: JsValue): HError = ???
  }

  def serveById = path("api" / "v1" / "applications" / "serve" / LongNumber / Segment) { (serviceId, signatureName) =>
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
                      gatewayPredictionService.serveJsonApplication(
                        JsonServeRequest(
                          targetId = serviceId,
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


  val routes =
    serveById
}
