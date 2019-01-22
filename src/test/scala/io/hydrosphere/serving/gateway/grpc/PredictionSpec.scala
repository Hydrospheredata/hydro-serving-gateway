package io.hydrosphere.serving.gateway.grpc

import cats.Monad
import cats.data.{Ior, WriterT}
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import io.hydrosphere.serving.gateway.service.application.ExecutionUnit
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import org.scalatest.{FunSpec, Matchers}

class PredictionSpec extends FunSpec with Matchers {

  val executionUnit = ExecutionUnit(
    "a",
    "path",
    applicationRequestId = Some("reqId"),
    signatureName = "sigName",
    applicationId = 1L,
    modelVersionId = 1,
    stageId = "stageId",
    applicationNamespace = None
  )

  val emptyRequest = PredictRequest(None, Map.empty)
  val emptyResponse = PredictResponse(Map.empty, Map.empty)
  val emptyResponseWithMeta = PredictionWithMetadata(emptyResponse, None, None)

  describe("call reporting") {

    def testReporting(ref: Ref[IO, List[PredictionWithMetadata.PredictionOrException]]): Reporting[IO] = {
      new Reporting[IO] {
        def report(
          req: PredictRequest,
          eu: ExecutionUnit,
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

      out.head shouldBe a[Left[Throwable, PredictionWithMetadata]]
    }

  }

}
