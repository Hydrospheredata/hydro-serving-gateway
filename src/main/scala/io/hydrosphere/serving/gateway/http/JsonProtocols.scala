package io.hydrosphere.serving.gateway.http

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.hydrosphere.serving.gateway.persistence.application.{StoredApplication, StoredService, StoredStage}
import io.hydrosphere.serving.model.api.Result.{ClientError, ErrorCollection, HError, InternalError}
import spray.json._

trait JsonProtocols extends DefaultJsonProtocol with SprayJsonSupport {
  
  implicit def internalErrorFormat[T <: Throwable] = new JsonWriter[InternalError[T]] {
    override def write(obj: InternalError[T]): JsValue = {
      val fields = Map(
        "exception" -> JsString(obj.exception.getMessage)
      )
      val reasonField = obj.reason.map { r =>
        Map("reason" -> JsString(r))
      }.getOrElse(Map.empty)

      JsObject(fields ++ reasonField)
    }
  }

  implicit val clientErrorFormat = jsonFormat1(ClientError.apply)

  implicit val errorFormat = new JsonWriter[HError] {
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
  }
  
  implicit def seqWrite[T :JsonWriter] = new JsonWriter[Seq[T]] {
    def write(list: Seq[T]) = JsArray(list.map(_.toJson).toVector)
  }

  implicit val gwService = new JsonWriter[StoredService] {
    override def write(obj: StoredService): JsValue = {
      JsObject(
        "host" -> JsString(obj.host),
        "port" -> JsNumber(obj.port),
        "weight" -> JsNumber(obj.weight)
      )
    }
  }
  
  implicit val gwStageFormat = new JsonWriter[StoredStage] {
    override def write(obj: StoredStage): JsValue = {
      JsObject(
        "id" -> JsString(obj.id),
        "services" -> obj.services.toJson
      )
    }

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