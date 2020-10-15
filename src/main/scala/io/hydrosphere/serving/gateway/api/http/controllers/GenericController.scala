package io.hydrosphere.serving.gateway.api.http.controllers

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.server.Directives
import cats.effect.implicits._
import cats.effect.{Effect, Sync}
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.api.http.JsonProtocols
import io.hydrosphere.serving.gateway.util.proto_json.SignatureBuilder
import io.hydrosphere.serving.http.TracingHeaders
import io.hydrosphere.serving.model.api.ValidationError
import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.TypedTensorFactory
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

trait GenericController extends JsonProtocols with Logging with Directives {
  def completeF[F[_], A](body : => F[A])(implicit F: Effect[F], m: ToResponseMarshaller[A]) = {
    complete {
      body.toIO.unsafeToFuture()
    }
  }

  def optionalTracingHeaders = optionalHeaderValueByName(TracingHeaders.xRequestId) &
    optionalHeaderValueByName(TracingHeaders.xB3TraceId) &
    optionalHeaderValueByName(TracingHeaders.xB3SpanId)

  def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.view.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v))).toMap
    JsObject(fields)
  }

  def decodeValidationError(err: ValidationError): String = {
    err match {
      case ValidationError.SignatureMissingError(expectedSignature, modelContract) =>
        s"No signature found with name '${expectedSignature}' in contract '${modelContract.modelName}'"
      case ValidationError.SignatureValidationError(suberrors, modelSignature) =>
        s"Signature '${modelSignature.signatureName}' validation errors: ${suberrors.map(decodeValidationError).mkString}"
      case ValidationError.FieldMissingError(expectedField) =>
        s"Missing '${expectedField}' field"
      case ValidationError.ComplexFieldValidationError(suberrors, field) =>
        s"Validation for '${field}' map field failed: ${suberrors.map(decodeValidationError).mkString}"
      case ValidationError.IncompatibleFieldTypeError(field, expectedType) =>
        s"Field '$field' with incompatible data type $expectedType"
      case ValidationError.UnsupportedFieldTypeError(expectedType) =>
        s"Data type $expectedType is not supported"
      case ValidationError.IncompatibleFieldShapeError(field, expectedShape) =>
        s"Incompatible shape for '${field.name}'"
      case ValidationError.InvalidFieldData(actualClass) =>
        s"Invalid data $actualClass"
    }
  }

  def jsonToRequest[F[_]](
    name: String,
    inputs: JsObject,
    signanture: ModelSignature
  )(implicit F: Sync[F]): F[PredictRequest] = F.defer {
    new SignatureBuilder(signanture).convert(inputs) match {
      case Left(value) =>
        val errorMsg = decodeValidationError(value)
        F.raiseError(GatewayError.InvalidArgument(errorMsg))
      case Right(tensors) =>
        F.delay(PredictRequest(
          modelSpec = Some(
            ModelSpec(
              name = name,
              signatureName = signanture.signatureName,
            )
          ),
          inputs = tensors.view.mapValues(_.toProto).toMap
        ))
    }
  }
}