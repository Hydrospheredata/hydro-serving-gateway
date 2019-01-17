package io.hydrosphere.serving.gateway.grpc

import cats.Monad
import cats.data.{Ior, WriterT}
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import io.hydrosphere.serving.gateway.service.application.{ExecutionUnit, StageInfo}
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import org.scalatest.{FunSpec, Matchers}

class PredictionSpec extends FunSpec with Matchers {

  val stageInfo = StageInfo(
    applicationRequestId = Some("reqId"),
    signatureName = "sigName",
    applicationId = 1L,
    modelVersionId = None,
    stageId = "stageId",
    applicationNamespace = None,
    dataProfileFields = Map.empty
  )
  val executionUnit = ExecutionUnit("a", "path", stageInfo)

  val emptyRequest = PredictRequest(None, Map.empty)
  val emptyResponse = PredictResponse(Map.empty, Map.empty)

  describe("call reporting") {

    type RespOrError = ExecutionInformation.ResponseOrError
    def testReporting(ref: Ref[IO, List[RespOrError]]): Reporting[IO] = {
      new Reporting[IO] {
        def report(
          req: PredictRequest,
          eu: ExecutionUnit,
          value: ExecutionInformation.ResponseOrError): IO[Unit] = ref.update(acc => value :: acc)
      }
    }

    it("should report success") {
      val ref = Ref.unsafe[IO, List[RespOrError]](List.empty)
      val prediction = Prediction.create0[IO](
        (_, _, _) => IO.pure(emptyResponse),
        testReporting(ref)
      )

      val out = (prediction.predict(executionUnit, emptyRequest, None).attempt *> ref.get)
        .unsafeRunSync()

      out.head shouldBe ResponseOrError.Response(emptyResponse)
    }

    it("should report error") {
      val ref = Ref.unsafe[IO, List[RespOrError]](List.empty)
      val prediction = Prediction.create0[IO](
        (_, _, _) => IO.raiseError(new Exception("err")),
        testReporting(ref)
      )

      val out = (prediction.predict(executionUnit, emptyRequest, None).attempt *> ref.get)
        .unsafeRunSync()

      out.head shouldBe a[ResponseOrError.Error]
    }

  }

}
