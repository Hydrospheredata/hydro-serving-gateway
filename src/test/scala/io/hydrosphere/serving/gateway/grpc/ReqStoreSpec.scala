package io.hydrosphere.serving.gateway.grpc

import cats.effect.IO
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import io.hydrosphere.serving.tensorflow.tensor.TensorProto
import org.scalatest.FunSpec

class ReqStoreSpec extends FunSpec {

  it("asdsad") {
    val dest = Destination.HostPort("localhost", 7265, "http")
    val store = ReqStore.create[IO](dest)

    val req = PredictRequest(None, Map.empty)
    val out = store.save("yoyo", req).unsafeRunSync()
    println(out)
  }
}
