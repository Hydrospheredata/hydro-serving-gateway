package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.hydrosphere.serving.gateway.persistence.application.{StoredApplication, StoredExecutionGraph, StoredService, StoredStage}
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

  implicit val gwService = jsonFormat3(StoredService.apply)
  implicit val gwStageFormat = new RootJsonFormat[StoredStage] {
    override def write(obj: StoredStage): JsValue = {
      JsObject(
        "id" -> JsString(obj.id),
        "services" -> obj.services.toJson
      )
    }

    override def read(json: JsValue): StoredStage = throw DeserializationException("GWStage reader is not implemented")
  }
  implicit val gwExecGraphFormat = jsonFormat1(StoredExecutionGraph.apply)
  implicit val gwAppFormat = new RootJsonFormat[StoredApplication] {
    override def write(obj: StoredApplication): JsValue = {
      JsObject(
        "id" -> JsNumber(obj.id),
        "name" -> JsString(obj.name),
        "executionGraph" -> obj.executionGraph.toJson
      )
    }

    override def read(json: JsValue): StoredApplication = throw DeserializationException("GWApplication reader is not implemented")
  }
}

object JsonProtocols extends JsonProtocols