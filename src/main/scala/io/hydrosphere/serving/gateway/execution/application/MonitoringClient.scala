package io.hydrosphere.serving.gateway.execution.application

import cats.data.OptionT
import cats.effect.{Async, Concurrent, Timer}
import cats.implicits._
import io.hydrosphere.serving.gateway.Logging
import io.hydrosphere.serving.gateway.config.ReqStoreConfig
import io.hydrosphere.serving.gateway.execution.Types.ServingReqStore
import io.hydrosphere.serving.gateway.execution.servable.ServableRequest
import io.hydrosphere.serving.gateway.integrations.Monitoring
import io.hydrosphere.serving.gateway.integrations.reqstore.ReqStore
import io.hydrosphere.serving.gateway.persistence.StoredModelVersion
import io.hydrosphere.serving.gateway.util.CircuitBreaker
import io.hydrosphere.serving.monitoring.api.ExecutionInformation
import io.hydrosphere.serving.monitoring.api.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.metadata.{ApplicationInfo, ExecutionError, ExecutionMetadata, TraceData}
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}

import scala.concurrent.duration._

trait MonitoringClient[F[_]] {
  def monitor(
    request: ServableRequest,
    response: AssociatedResponse,
    appInfo: Option[ApplicationInfo]
  ): F[ExecutionMetadata]
}

object MonitoringClient extends Logging {
  def mkExecutionMetadata(
    modelVersion: StoredModelVersion,
    replayTrace: Option[TraceData],
    rsTraceData: Option[TraceData],
    appInfo: Option[ApplicationInfo],
    latency: Double,
    requestId: String
  ) = {
    ExecutionMetadata(
      signatureName = modelVersion.predict.signatureName,
      modelVersionId = modelVersion.id,
      modelName = modelVersion.name,
      modelVersion = modelVersion.version,
      traceData = rsTraceData,
      requestId = requestId,
      appInfo = appInfo,
      latency = latency,
      originTraceData = replayTrace
    )
  }

  def make[F[_]](
    monitoring: Monitoring[F],
    maybeReqStore: Option[ServingReqStore[F]]
  )(implicit F: Concurrent[F], timer: Timer[F]): MonitoringClient[F] = new MonitoringClient[F] {
    override def monitor(request: ServableRequest, response: AssociatedResponse, appInfo: Option[ApplicationInfo]): F[ExecutionMetadata] = {
      val mv = response.servable.modelVersion
      val wrappedRequest = PredictRequest(
        inputs = request.data,
        modelSpec = ModelSpec(
          name = mv.name,
          version = mv.version.some,
          signatureName = mv.predict.signatureName
        ).some
      )
      val wrappedResponse = response.resp.data match {
        case Left(err) => ExecutionInformation.ResponseOrError.Error(ExecutionError(err.toString))
        case Right(value) => ExecutionInformation.ResponseOrError.Response(PredictResponse(value))
      }
      val metaWithoutTrace = mkExecutionMetadata(
          mv,
          request.replayTrace,
          None,
          appInfo,
          response.resp.latency,
          request.requestId
        )
      val flow = for {
        maybeTraceData <- maybeReqStore.toOptionT[F].flatMap { rs =>
          val listener = (st: CircuitBreaker.Status) => Logging.info(s"Restore circuit breaker status was changed: $st")
          val cb = CircuitBreaker[F](3 seconds, 5, 30 seconds)(listener)
          val res = cb.use(rs.save(mv.id.toString, wrappedRequest -> wrappedResponse))
            .attempt.map(_.toOption)
          OptionT(res)
        }.value
        execMeta = metaWithoutTrace.copy(traceData = maybeTraceData)
        execInfo = ExecutionInformation(wrappedRequest.some, execMeta.some, wrappedResponse)
        _ <- monitoring.send(execInfo)
      } yield execMeta

      flow.handleErrorWith { error =>
        F.delay {
          logger.error("Can't send data to shadow.", error)
          metaWithoutTrace
        }
      }
    }
  }

  def mkReqStore[F[_]](conf: ReqStoreConfig)(implicit F: Async[F]): F[Option[ServingReqStore[F]]] = {
    if (conf.enabled) {
      ReqStore.create[F, (PredictRequest, ResponseOrError)](conf).map(_.some)
    } else {
      F.pure(None)
    }
  }
}