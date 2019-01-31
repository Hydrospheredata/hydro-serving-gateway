package io.hydrosphere.serving.gateway.grpc.reqstore

import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.TraceData
import io.hydrosphere.serving.tensorflow.api.predict.PredictResponse
import io.hydrosphere.serving.tensorflow.tensor.TypedTensorFactory
import spray.json._

import scala.util.Try

object jsonCodecs {

  implicit val traceDataFormat = new JsonReader[TraceData] {

    private def extractLong(v: JsValue): Option[Long] = v match {
      case n: JsNumber => Try(n.value.toLongExact).toOption
      case _ => None
    }

    override def read(json: JsValue): TraceData = {
      json match {
        case x @ JsObject(fields) =>
          val mTs = fields.get("ts").flatMap(extractLong)
          val mUid = fields.get("uniq").flatMap(extractLong)
          (mTs, mUid) match {
            case (Some(ts), Some(uid)) => TraceData(ts, uid)
            case _ => throw new DeserializationException(s"Invalid trace data format: $x")
          }
        case x => throw new DeserializationException(s"Invalid trace data format: $x")
      }
    }
  }

  implicit val predictResponse = new JsonWriter[PredictResponse] {
    override def write(r: PredictResponse): JsValue = {
      val fields = r.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
      JsObject(fields)
    }
  }

  implicit val responseOrError = new JsonWriter[ResponseOrError] {
    override def write(ror: ResponseOrError): JsValue = ror match {
      case ResponseOrError.Response(r) =>
        val fields =r.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
        JsObject(fields + ("type" -> JsString("response")))
      case ResponseOrError.Error(e) =>
        JsObject("type" -> JsString("error"), "error" -> JsString(e.errorMessage))
      case ResponseOrError.Empty =>
        JsObject("type" -> JsString("empty"))
    }
  }
}
