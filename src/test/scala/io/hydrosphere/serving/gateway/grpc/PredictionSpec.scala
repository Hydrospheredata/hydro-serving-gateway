package io.hydrosphere.serving.gateway.grpc

import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import io.hydrosphere.serving.gateway.persistence.application.PredictDownstream
import io.hydrosphere.serving.gateway.service.application.{ExecutionMeta, ExecutionUnit}
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.Future

class PredictionSpec extends FunSpec with Matchers {

  val client = new PredictDownstream {
    override def send(req: PredictRequest): Future[PredictResponse] = ???

    override def close(): Future[Unit] = ???
  }
  val executionUnit = ExecutionUnit(
      meta = ExecutionMeta(
        "a",
        "path",
        applicationRequestId = Some("reqId"),
        signatureName = "sigName",
        applicationId = 1L,
        modelVersionId = 1,
        stageId = "stageId",
        applicationNamespace = None
      ),
      client = client
  )

  val emptyRequest = PredictRequest(None, Map.empty)
  val emptyResponse = PredictResponse(Map.empty, Map.empty)
  val emptyResponseWithMeta = PredictionWithMetadata(emptyResponse, None, None)

  describe("call reporting") {

    def testReporting(ref: Ref[IO, List[PredictionWithMetadata.PredictionOrException]]): Reporting[IO] = {
      new Reporting[IO] {
        def report(
          req: PredictRequest,
          eu: ExecutionMeta,
          value: PredictionWithMetadata.PredictionOrException): IO[Unit] = ref.update(acc => value :: acc)
      }
    }

    it("should report success") {
      val ref = Ref.unsafe[IO, List[PredictionWithMetadata.PredictionOrException]](List.empty)
      val prediction = Prediction.create0[IO](
        (_, _, _) => IO.pure(emptyResponseWithMeta),
        testReporting(ref)
      )

      val out = (prediction.predict(executionUnit, emptyRequest, None).attempt *> ref.get)
        .unsafeRunSync()

      out.head shouldBe Right(emptyResponseWithMeta)
    }

    it("should report error") {
      val ref = Ref.unsafe[IO, List[PredictionWithMetadata.PredictionOrException]](List.empty)
      val prediction = Prediction.create0[IO](
        (_, _, _) => IO.raiseError(new Exception("err")),
        testReporting(ref)
      )

      val out = (prediction.predict(executionUnit, emptyRequest, None).attempt *> ref.get)
        .unsafeRunSync()

      out.head shouldBe a[Left[_, _]]
    }

  }

}
