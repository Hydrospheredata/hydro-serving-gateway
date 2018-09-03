package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.hydrosphere.serving.gateway.service.{GWApplication, GWExecutionGraph, GWService, GWStage}
import io.hydrosphere.serving.model.api.Result.{ClientError, ErrorCollection, HError, InternalError}
import spray.json._

trait JsonProtocols extends DefaultJsonProtocol with SprayJsonSupport {
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

  implicit val gwService = jsonFormat3(GWService.apply)
  implicit val gwStageFormat = new RootJsonFormat[GWStage] {
    override def write(obj: GWStage): JsValue = {
      JsObject(
        "id" -> JsString(obj.id),
        "services" -> obj.services.toJson
      )
    }

    override def read(json: JsValue): GWStage = throw new DeserializationException("GWStage reader is not implemented")
  }
  implicit val gwExecGraphFormat = jsonFormat1(GWExecutionGraph.apply)
  implicit val gwAppFormat = new RootJsonFormat[GWApplication] {
    override def write(obj: GWApplication): JsValue = {
      JsObject(
        "id" -> JsNumber(obj.id),
        "name" -> JsString(obj.name),
        "executionGraph" -> obj.executionGraph.toJson
      )
    }

    override def read(json: JsValue): GWApplication = throw new DeserializationException("GWApplication reader is not implemented")
  }
}

object JsonProtocols extends JsonProtocols