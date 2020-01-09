package io.hydrosphere.serving.gateway.util.proto_json

import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.model.api.ValidationError
import io.hydrosphere.serving.model.api.ValidationError.{FieldMissingError, IncompatibleFieldTypeError}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.tensor.MapTensor
import io.hydrosphere.serving.tensorflow.types.DataType
import spray.json._

class ComplexFieldBuilder(val modelField: ModelField, val subfields: Seq[ModelField]) {

  def convert(data: JsValue): Either[Seq[ValidationError], MapTensor] = {
    data match {
      case arr: JsArray =>
        val eithers = arr.elements.map(convert)
        val errors = eithers.filter(_.isLeft).map(_.left.get)

        if (errors.nonEmpty) {
          Left(errors.flatten)
        } else {
          val results = eithers.map(_.right.get)
          Right(
            MapTensor(
              TensorShape(modelField.shape),
              results.map(_.data.head)
            )
          )
        }

      case obj: JsObject =>
        val a = subfields.map { field =>
          obj.getFields(field.name).headOption match {
            case None => Left(FieldMissingError(field.name))

            case Some(fieldData) =>
              val fieldValidator = new ModelFieldBuilder(field)
              fieldValidator.convert(fieldData).right.map { tensor =>
                field.name -> tensor
              }
          }
        }
        if (a.exists(_.isLeft)) {
          val errors = a.collect { case Left(err) => err }
          Left(errors)
        } else {
          val tensors = a.collect { case Right((name, tensor)) => name -> tensor }.toMap
          Right(
            MapTensor(
              TensorShape(modelField.shape),
              Seq(tensors)
            )
          )
        }

      case _ => Left(Seq(IncompatibleFieldTypeError(modelField.name, DataType.DT_MAP)))
    }
  }

}
