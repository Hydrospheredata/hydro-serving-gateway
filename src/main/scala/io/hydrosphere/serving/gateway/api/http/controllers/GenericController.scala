package io.hydrosphere.serving.gateway.api.http.controllers

import akka.http.scaladsl.server.Directives.optionalHeaderValueByName
import cats.effect.Sync
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.api.http.JsonProtocols
import io.hydrosphere.serving.http.TracingHeaders
import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.TypedTensorFactory
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

trait GenericController extends JsonProtocols with Logging {
  def optionalTracingHeaders = optionalHeaderValueByName(TracingHeaders.xRequestId) &
    optionalHeaderValueByName(TracingHeaders.xB3TraceId) &
    optionalHeaderValueByName(TracingHeaders.xB3SpanId)

  def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
    JsObject(fields)
  }

  def jsonToRequest[F[_]](
    name: String,
    version: Option[Long],
    inputs: JsObject,
    signanture: ModelSignature
  )(implicit F: Sync[F]): F[PredictRequest] = F.defer {
    new SignatureBuilder(signanture).convert(inputs) match {
      case Left(value) =>
        F.raiseError(GatewayError.InvalidArgument(s"Validation error: $value"))
      case Right(tensors) =>
        F.delay(PredictRequest(
          modelSpec = Some(
            ModelSpec(
              name = name,
              signatureName = signanture.signatureName,
              version = version
            )
          ),
          inputs = tensors.mapValues(_.toProto)
        ))
    }
  }
}