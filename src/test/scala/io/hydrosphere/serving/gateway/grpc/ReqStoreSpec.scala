package io.hydrosphere.serving.gateway.grpc

import java.io.ByteArrayOutputStream

import cats.effect.IO
import com.google.protobuf.CodedOutputStream
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import org.scalatest.FunSpec

class ReqStoreSpec extends FunSpec {

  it("asdsad") {
//    val dest = Destination.HostPort("localhost", 7265, "http")
//    val store = ReqStore.create[IO](dest)
//
//    val req = PredictRequest(None, Map.empty)
//    val out = store.save("yoyo", req).unsafeRunSync()
//    println(out)

    val req = PredictRequest(None, Map.empty)
    val x = req.serializedSize
    println(x)
    req
    val os = new ByteArrayOutputStream(x)
    val os1 = CodedOutputStream.newInstance(os)

    req.writeTo(os1)
    val out = os.toByteArray
    println(out.length)
  }
}
