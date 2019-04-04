package io.hydrosphere.serving.gateway.grpc

import cats.effect.Effect
import cats.effect.syntax.effect._
import cats.syntax.monadError._
import com.google.protobuf.empty.Empty
import io.hydrosphere.serving.gateway.GatewayError.InvalidArgument
import io.hydrosphere.serving.gateway.service.application.{ApplicationExecutionService, RequestTracingInfo}
import io.hydrosphere.serving.grpc.Headers
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc.PredictionService
import io.hydrosphere.serving.tensorflow.api.prediction_service.StatusResponse
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future

class GrpcPredictionServiceImpl[F[_]: Effect](
  gatewayPredictionService: ApplicationExecutionService[F]
) extends PredictionService with Logging {

  override def predict(request: PredictRequest): Future[PredictResponse] = {
    logger.info(s"Got request from G modelSpec=${request.modelSpec}")
    request.modelSpec match {
      case Some(_) =>
        val requestId = Option(Headers.XRequestId.contextKey.get())
        val tracingInfo = requestId.map(r => RequestTracingInfo(
          xRequestId = r,
          xB3requestId = Option(Headers.XB3TraceId.contextKey.get()),
          xB3SpanId = Option(Headers.XB3SpanId.contextKey.get())
        ))
        val resultF = gatewayPredictionService.serveGrpcApplication(request, tracingInfo)
        resultF.toIO.attempt.map {
          case Right(result) =>
            logger.info("Got successful GRPC response")
            Right(result)
          case Left(error) =>
            logger.warn("Got an error from GRPC", error)
            Left(error)
        }.rethrow.unsafeToFuture()

      case None => Future.failed(InvalidArgument("ModelSpec is not defined"))
    }
  }

  override def status(request: Empty): Future[StatusResponse] = Future.successful(
    StatusResponse(
      status = StatusResponse.ServiceStatus.SERVING,
      message = "I'm ready"
    )
  )
}
