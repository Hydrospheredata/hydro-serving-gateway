package io.hydrosphere.serving.gateway.grpc

import cats.effect.IO
import io.hydrosphere.serving.gateway.grpc.reqstore.{Destination, ReqStore}
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import org.scalatest.FunSpec

class ReqStoreSpec extends FunSpec {

  describe("sending") {
    val dest = Destination.HostPort("localhost", 7265, "http")
    val store = ReqStore.create[IO, PredictRequest](dest).unsafeRunSync()

    it("sends empty message") {
      val req = PredictRequest(None, Map.empty)
      store.save("yoyo", req).unsafeRunSync()
    }

  }
}
