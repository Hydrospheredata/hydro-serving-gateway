package io.hydrosphere.serving.gateway.grpc

import cats._
import cats.effect._
import cats.implicits._
import io.hydrosphere.serving.gateway.config.Configuration
import io.hydrosphere.serving.gateway.service.application.{ExecutionUnit, RequestTracingInfo}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.TensorProto
import io.hydrosphere.serving.tensorflow.types.DataType

import scala.concurrent.duration.{Duration, MILLISECONDS}
import scala.util.Try


trait Prediction[F[_]] {

  def predict(
    unit: ExecutionUnit,
    request: PredictRequest,
    tracingInfo: Option[RequestTracingInfo]
  ): F[PredictionWithMetadata]

}

//TODO: tracingInfo doesn't work
//TODO: remove or integrate open-tracing
object Prediction {

  type PredictionStub = PredictionServiceGrpc.PredictionServiceStub
  type PredictFunc[F[_]] = (ExecutionUnit, PredictRequest, Option[RequestTracingInfo]) => F[PredictionWithMetadata]

  def create[F[_]](conf: Configuration)(implicit F: Concurrent[F], cs: ContextShift[F]): F[Prediction[F]] = {
    val predictF = predictWithLatency(Clock.create[F])
    val mkReporting = if (conf.application.shadowingOn) {
      Reporting.default[F](conf)
    } else {
      F.pure(Reporting.noop[F])
    }
    mkReporting map  (create0(predictF, _))
  }

  def create0[F[_]](exec: PredictFunc[F], reporting: Reporting[F])(
    implicit F: MonadError[F, Throwable]): Prediction[F] = {

    new Prediction[F] {

      def predict(
        eu: ExecutionUnit,
        req: PredictRequest,
        tracingInfo: Option[RequestTracingInfo]
      ): F[PredictionWithMetadata] = {

        exec(eu, req, tracingInfo)
          .attempt
          .flatTap(out => reporting.report(req, eu.meta, out))
          .rethrow
      }
    }
  }

  def predictWithLatency[F[_]](clock: Clock[F])(
    implicit F: Monad[F], L: LiftIO[F]): PredictFunc[F] = {

    (eu: ExecutionUnit, req: PredictRequest, tracingInfo: Option[RequestTracingInfo]) => {

      for {
        start <- clock.monotonic(MILLISECONDS)
        resp  <- L.liftIO(IO.fromFuture(IO(eu.client.send(req))))
        stop  <- clock.monotonic(MILLISECONDS)
      } yield {
         val latency = stop - start

        val resultWithInternalInfo = resp.addInternalInfo(
          "system.latency" -> TensorProto(
            dtype = DataType.DT_INT64,
            int64Val = Seq(latency),
            tensorShape = TensorShape.scalar.toProto
          )
        )
  
        PredictionWithMetadata(
          response = resultWithInternalInfo,
          modelVersionId = eu.meta.modelVersionId.toString.some,
          latency = latency.toString.some
        )
      }
    }
  }


}
