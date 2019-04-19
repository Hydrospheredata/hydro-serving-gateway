package io.hydrosphere.serving.gateway.grpc

import cats.effect.IO
import io.hydrosphere.serving.gateway.persistence.application.{PredictDownstream, PredictOut}
import io.hydrosphere.serving.gateway.service.application.{ExecutionMeta, ExecutionUnit}
import io.hydrosphere.serving.monitoring.api.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.metadata.ExecutionMetadata
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.Future

class PredictionSpec extends FunSpec with Matchers {
  
  
  it("reqstore shouldn't affect prediction") {
  
    val out = PredictOut(Right(PredictResponse()), 1L, 1L)
    
    val prediction = Prediction.create[IO](
      (eu: ExecutionUnit, req: PredictRequest) => IO.fromFuture(IO(eu.client.send(req))),
      (_: String, _: PredictRequest, _: ResponseOrError) => IO.raiseError(new Exception()),
      (_: PredictRequest, _: ResponseOrError, _: ExecutionMetadata) => IO.unit
    )
    
    val meta = ExecutionMeta("name", "path", None, "sigName", 1L, "stageId", None)
    val execUnit = ExecutionUnit(mkPredictDownStream(_ => Future.successful(out)), meta)
    
    val result = prediction.predict(execUnit, PredictRequest()).attempt.unsafeRunSync()
    result.isRight shouldBe true
  }
  
  def mkPredictDownStream(f: PredictRequest => Future[PredictOut]): PredictDownstream = {
    new PredictDownstream {
      override def send(req: PredictRequest): Future[PredictOut] = f(req)
      override def close(): Future[Unit] = Future.unit
    }
  }
}
