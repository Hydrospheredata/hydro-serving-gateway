package io.hydrosphere.serving.gateway.api.http

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import cats.data.NonEmptyList
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredModelVersion, StoredServable, StoredStage}
import io.hydrosphere.serving.manager.data_profile_types.DataProfileType
import io.hydrosphere.serving.manager.grpc.entities.{DockerImage, HostSelector, Model, ModelVersion}
import io.hydrosphere.serving.tensorflow.tensor_shape.TensorShapeProto
import io.hydrosphere.serving.tensorflow.types.DataType
import scalapb.{GeneratedEnum, GeneratedEnumCompanion}
import spray.json._

import scala.collection.mutable

trait JsonProtocols extends DefaultJsonProtocol with SprayJsonSupport {
  implicit def protoEnumFormat[T <: GeneratedEnum](enumCompanion: GeneratedEnumCompanion[T]): RootJsonFormat[T] = new RootJsonFormat[T] {
    override def write(obj: T): JsValue = {
      JsString(obj.toString())
    }

    override def read(json: JsValue): T = {
      json match {
        case JsString(str) =>
          enumCompanion.fromName(str)
            .getOrElse(throw DeserializationException(s"$str is invalid $enumCompanion"))
        case x => throw DeserializationException(s"$x is not a correct $enumCompanion")
      }
    }
  }

  implicit val dataProfileTypeFormat = protoEnumFormat(DataProfileType)

  implicit val dataTypeFormat = protoEnumFormat(DataType)

  implicit val tensorShapeDimFormat = jsonFormat2(TensorShapeProto.Dim.apply)
  implicit val tensorShapeFormat = jsonFormat2(TensorShapeProto.apply)

  implicit val modelFieldFormat = new RootJsonFormat[ModelField] {

    object DtypeJson {
      def unapply(arg: JsValue): Option[(JsString, Option[JsObject], Option[JsString], JsString)] = {
        arg match {
          case JsObject(fields) =>
            for {
              name <- fields.get("name")
              dtype <- fields.get("dtype")
            } yield (
              name.asInstanceOf[JsString],
              fields.get("shape").map(_.asInstanceOf[JsObject]),
              fields.get("profile").map(_.asInstanceOf[JsString]),
              dtype.asInstanceOf[JsString]
            )
          case _ => None
        }
      }
    }

    object SubfieldsJson {
      def unapply(arg: JsValue): Option[(JsString, Option[JsObject], JsArray)] = {
        arg match {
          case JsObject(fields) =>
            for {
              name <- fields.get("name")
              subfields <- fields.get("subfields")
            } yield (
              name.asInstanceOf[JsString],
              fields.get("shape").map(_.asInstanceOf[JsObject]),
              subfields.asInstanceOf[JsArray]
            )
          case _ => None
        }
      }
    }

    override def read(json: JsValue): ModelField = json match {
      case DtypeJson(name, shape, profileType, dtype) =>
        ModelField(
          name.value,
          shape.map(_.convertTo[TensorShapeProto]),
          profileType.flatMap(x => DataProfileType.fromName(x.value.toUpperCase)).getOrElse(DataProfileType.NONE),
          ModelField.TypeOrSubfields.Dtype(DataType.fromName(dtype.value).get)
        )

      case SubfieldsJson(name, shape, subs) =>
        val subfields = ModelField.TypeOrSubfields.Subfields(
          ModelField.Subfield(subs.elements.map(read))
        )
        ModelField(name.value, shape.map(_.convertTo[TensorShapeProto]), DataProfileType.NONE, subfields)

      case x => throw DeserializationException(s"Invalid ModelField: $x")
    }

    override def write(obj: ModelField): JsValue = {
      val fields = new mutable.HashMap[String, JsValue]()
      fields += "name" -> JsString(obj.name)
      fields += "profile" -> JsString(obj.profile.name)
      obj.shape.foreach { shape =>
        fields += "shape" -> shape.toJson
      }
      obj.typeOrSubfields match {
        case ModelField.TypeOrSubfields.Dtype(value) =>
          fields += "dtype" -> JsString(value.name)
        case ModelField.TypeOrSubfields.Subfields(value) =>
          fields += "subfields" -> JsArray(value.data.map(write).toVector)
        case ModelField.TypeOrSubfields.Empty => fields
      }
      JsObject(fields.toMap)
    }
  }

  implicit val modelSignatureFormat = jsonFormat3(ModelSignature.apply)
  implicit val modelContractFormat = jsonFormat2(ModelContract.apply)

  implicit val gwHostSelector = jsonFormat2(HostSelector.apply)
  implicit val gwModel = jsonFormat2(Model.apply)
  implicit val gwDockerImage = jsonFormat2(DockerImage.apply)

  implicit val storedModelVersion = jsonFormat5(StoredModelVersion.apply)

  implicit val gwService = jsonFormat5(StoredServable.apply)

  implicit def nelFormat[A: RootJsonFormat] = new RootJsonFormat[NonEmptyList[A]] {
    override def write(obj: NonEmptyList[A]): JsValue = {
      obj.toList.toJson
    }

    override def read(json: JsValue): NonEmptyList[A] = {
      NonEmptyList.fromList(json.convertTo[List[A]]) match {
        case Some(res) => res
        case None => throw DeserializationException(s"Can't parse JSON '$json' as NonEmptyList")
      }
    }
  }

  implicit val gwStageFormat = jsonFormat3(StoredStage.apply)

  implicit val gwAppFormat = jsonFormat5(StoredApplication.apply)
}

object JsonProtocols extends JsonProtocols