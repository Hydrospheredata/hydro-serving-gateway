package io.hydrosphere.serving.gateway.execution

import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.model.api.TensorUtil
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest

object RequestValidator {
 def verify(request: PredictRequest): Either[Map[String, GatewayError], PredictRequest] = {
  val verificationResults = request.inputs.map {
   case (name, tensor) =>
    val verifiedTensor = if (!tensor.tensorContent.isEmpty) { // tensorContent - byte field, thus skip verifications
     Right(tensor)
    } else {
     Either.fromOption(
      TensorUtil.verifyShape(tensor),
      GatewayError.InvalidTensorShape(s"$name tensor has invalid shape").asInstanceOf[GatewayError]
     )
    }
    name -> verifiedTensor
  }

  val errors = verificationResults.filter {
   case (_, t) => t.isLeft
  }.mapValues(_.left.get)

  if (errors.isEmpty) {
   val verifiedInputs = verificationResults.mapValues(_.right.get)
   Right(request.copy(inputs = verifiedInputs))
  } else {
   Left(errors)
  }
 }
}
