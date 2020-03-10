package io.hydrosphere.serving.gateway.util.proto_json

import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.contract.model_field.ModelField.TypeOrSubfields._
import io.hydrosphere.serving.model.api.ValidationError
import io.hydrosphere.serving.model.api.ValidationError.{ComplexFieldValidationError, FieldMissingError}
import io.hydrosphere.serving.tensorflow.tensor.TypedTensor
import spray.json._

class ModelFieldBuilder(val modelField: ModelField) {

  def convert(data: JsValue): Either[ValidationError, TypedTensor[_]] = {
    modelField.typeOrSubfields match {
      case Empty => Left(FieldMissingError(modelField.name))

      case Subfields(subfields) =>
        val complexFieldValidator = new ComplexFieldBuilder(modelField, subfields.data)
        complexFieldValidator.convert(data).left.map { errors =>
          ComplexFieldValidationError(errors, modelField)
        }

      case Dtype(value) =>
        val infoFieldValidator = new InfoFieldBuilder(modelField, value)
        infoFieldValidator.convert(data)
    }
  }

}
