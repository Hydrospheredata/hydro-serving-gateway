package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.hydrosphere.serving.gateway.persistence.application.{StoredApplication, StoredExecutionGraph, StoredService, StoredStage}
import spray.json._

trait JsonProtocols extends DefaultJsonProtocol with SprayJsonSupport {

  implicit val gwService = jsonFormat2(StoredService.apply)
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