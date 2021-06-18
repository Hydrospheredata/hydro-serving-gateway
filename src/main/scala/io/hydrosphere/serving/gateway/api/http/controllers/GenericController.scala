package io.hydrosphere.serving.gateway.api.http.controllers

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.server.Directives
import cats.effect.implicits._
import cats.effect.{Effect, Sync}
import io.circe._
import io.hydrosphere.serving.proto.contract.signature.ModelSignature
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.http.TracingHeaders
import io.hydrosphere.serving.proto.contract.errors.ValidationError
import io.hydrosphere.serving.proto.contract.tensor.builders.SignatureBuilder
import io.hydrosphere.serving.proto.runtime.api.PredictResponse
import io.hydrosphere.serving.proto.contract.tensor.definitions.TypedTensorFactory
import io.hydrosphere.serving.proto.contract.tensor.conversions.json.TensorJsonLens
import io.hydrosphere.serving.proto.gateway.api.GatewayPredictRequest
import org.apache.logging.log4j.scala.Logging

trait GenericController extends Logging with Directives {
  def completeF[F[_], A](body : => F[A])(implicit F: Effect[F], m: ToResponseMarshaller[A]) = {
    complete {
      body.toIO.unsafeToFuture()
    }
  }

  def optionalTracingHeaders = optionalHeaderValueByName(TracingHeaders.xRequestId) &
    optionalHeaderValueByName(TracingHeaders.xB3TraceId) &
    optionalHeaderValueByName(TracingHeaders.xB3SpanId)

  def responseToJsObject(rr: PredictResponse): Json = {
    Json.fromFields(rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v))))
  }

  def decodeValidationError(err: ValidationError): String = {
    err match {
      case ValidationError.SignatureValidationError(suberrors, modelSignature) =>
        s"Signature '${modelSignature.signatureName}' validation errors: ${suberrors.map(decodeValidationError).mkString}"
      case ValidationError.FieldMissingError(expectedField) =>
        s"Missing '${expectedField}' field"
      case ValidationError.NestedFieldValidationError(suberrors, field) =>
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
    inputs: Json,
    signanture: ModelSignature
  )(implicit F: Sync[F]): F[GatewayPredictRequest] = F.defer {
    new SignatureBuilder(signanture).convert(inputs) match {
      case Left(value) =>
        val errorMsg = decodeValidationError(value)
        F.raiseError(GatewayError.InvalidArgument(errorMsg))
      case Right(tensors) =>
        F.delay(GatewayPredictRequest(
          name = name,
          data = tensors.view.mapValues(_.toProto).toMap
        ))
    }
  }
}
