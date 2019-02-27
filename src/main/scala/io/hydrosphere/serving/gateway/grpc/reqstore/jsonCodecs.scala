package io.hydrosphere.serving.gateway.grpc.reqstore

import io.hydrosphere.serving.monitoring.monitoring.TraceData
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
}
