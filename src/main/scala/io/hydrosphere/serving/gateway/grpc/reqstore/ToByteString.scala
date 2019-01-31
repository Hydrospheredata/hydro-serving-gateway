package io.hydrosphere.serving.gateway.grpc.reqstore

import java.nio.charset.StandardCharsets

import akka.util.ByteString
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest


trait ToByteString[A] {
  def to(a: A): ByteString
}

object ToByteString {

  implicit val reqResp: ToByteString[(PredictRequest, ResponseOrError)] = {
    new ToByteString[(PredictRequest, ResponseOrError)] {

      import jsonCodecs._
      import spray.json._

      override def to(a: (PredictRequest, ResponseOrError)): ByteString = {
        val reqJson = a._2.toJson
        val respJson = a._2.toJson
        val all = JsObject(
          "request" -> reqJson,
          "response" -> respJson
        )
        ByteString(all.compactPrint, StandardCharsets.UTF_8)
      }
    }
  }
}

