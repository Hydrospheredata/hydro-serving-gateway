package io.hydrosphere.serving.gateway.util.proto_json

import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.data_profile_types.DataProfileType
import io.hydrosphere.serving.model.api.ValidationError
import io.hydrosphere.serving.model.api.ValidationError.SignatureValidationError
import io.hydrosphere.serving.tensorflow.tensor.TypedTensor
import spray.json._

class SignatureBuilder(val signature: ModelSignature) {
  def convert(data: JsObject): Either[ValidationError, Map[String, TypedTensor[_]]] = {
    // rootField is a virtual field that aggregates all request inputs
    val rootField = ModelField(
      "root",
      None,
      DataProfileType.NONE,
      ModelField.TypeOrSubfields.Subfields(
        ModelField.Subfield(
          signature.inputs
        )
      )
    )

    val fieldValidator = new ComplexFieldBuilder(rootField, signature.inputs)
    fieldValidator.convert(data) match {
      case Left(errors) =>
        Left(SignatureValidationError(errors, signature))
      case Right(tensor) =>
        Right(tensor.data.head)
    }
  }
}