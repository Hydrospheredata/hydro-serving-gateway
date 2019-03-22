package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.hydrosphere.serving.gateway.persistence.application.{StoredApplication, StoredService, StoredStage}
import spray.json._

trait JsonProtocols extends DefaultJsonProtocol with SprayJsonSupport {

  implicit val gwService = jsonFormat3(StoredService.apply)
  
  implicit val gwStageFormat = new JsonWriter[StoredStage] {
    override def write(obj: StoredStage): JsValue = {
      JsObject(
        "id" -> JsString(obj.id),
        "services" -> obj.services.toList.toJson
      )
    }

  }

  implicit def seqWrite[T: JsonWriter] = new JsonWriter[Seq[T]] {
    def write(list: Seq[T]) = JsArray(list.map(_.toJson).toVector)
  }
  
  implicit val gwAppFormat = new RootJsonFormat[StoredApplication] {
    override def write(obj: StoredApplication): JsValue = {
      JsObject(
        "id" -> JsNumber(obj.id),
        "name" -> JsString(obj.name),
        "stages" -> obj.stages.toJson
      )
    }
  
    override def read(json: JsValue): StoredApplication = ???
  }
}

object JsonProtocols extends JsonProtocols