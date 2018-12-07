package io.hydrosphere.serving.gateway.grpc

import java.util.concurrent.atomic.AtomicReference

import cats.effect.{Effect, IO}
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.service.application.{ExecutionUnit, RequestTracingInfo}
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Headers}
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionInformation, MonitoringServiceGrpc}
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import org.apache.logging.log4j.scala.Logging

trait PredictionServiceAlg[F[_]] {
  def sendToExecUnit(unit: ExecutionUnit, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse]
  def sendToMonitoring(execInfo: ExecutionInformation): F[Unit]
  def sendToProfiling(execInfo: ExecutionInformation): F[Unit]
}

class PredictionServiceImpl[F[_]: Effect](
  val appConfig: ApplicationConfig,
  val predictGrpcClient: PredictionServiceGrpc.PredictionServiceStub,
  val profilerGrpcClient: DataProfilerServiceGrpc.DataProfilerServiceStub,
  val monitoringGrpcClient: MonitoringServiceGrpc.MonitoringServiceStub
) extends PredictionServiceAlg[F] with Logging {

  override def sendToExecUnit(unit: ExecutionUnit, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    val modelVersionIdHeaderValue = new AtomicReference[String](null)
    val latencyHeaderValue = new AtomicReference[String](null)

    val deadline = appConfig.grpc.deadline

    var requestBuilder = predictGrpcClient
      .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, unit.serviceName)
      .withOption(Headers.XServingModelVersionId.callOptionsClientResponseWrapperKey, modelVersionIdHeaderValue)
      .withOption(Headers.XEnvoyUpstreamServiceTime.callOptionsClientResponseWrapperKey, latencyHeaderValue)
      .withDeadlineAfter(deadline.length, deadline.unit)

    if (tracingInfo.isDefined) {
      val tr = tracingInfo.get
      requestBuilder = requestBuilder
        .withOption(Headers.XRequestId.callOptionsKey, tr.xRequestId)

      if (tr.xB3requestId.isDefined) {
        requestBuilder = requestBuilder
          .withOption(Headers.XB3TraceId.callOptionsKey, tr.xB3requestId.get)
      }

      if (tr.xB3SpanId.isDefined) {
        requestBuilder = requestBuilder
          .withOption(Headers.XB3ParentSpanId.callOptionsKey, tr.xB3SpanId.get)
      }
    }

    IO.fromFuture(
      IO(requestBuilder.predict(request))
    ).to[F]
  }

  override def sendToMonitoring(execInfo: ExecutionInformation): F[Unit] = {
    val deadline = appConfig.grpc.deadline

    def send = monitoringGrpcClient
      .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, appConfig.monitoringDestination)
      .withDeadlineAfter(deadline.length, deadline.unit)
      .analyze(execInfo)

    for {
      _ <- IO.fromFuture(IO(send)).to[F]
      unit <- IO.unit.to[F]
    } yield unit

  }

  override def sendToProfiling(execInfo: ExecutionInformation): F[Unit] = {
    val deadline = appConfig.grpc.deadline

    def send = profilerGrpcClient
      .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, appConfig.profilingDestination)
      .withDeadlineAfter(deadline.length, deadline.unit)
      .analyze(execInfo)

    for {
      _ <- IO.fromFuture(IO(send)).to[F]
      unit <- IO.unit.to[F]
    } yield unit
  }
}
