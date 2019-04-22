package io.hydrosphere.serving.gateway.execution.application

import cats.data.OptionT
import cats.effect.{Async, Concurrent, Timer}
import cats.implicits._
import io.hydrosphere.serving.gateway.config.ReqStoreConfig
import io.hydrosphere.serving.gateway.integrations.Monitoring
import io.hydrosphere.serving.gateway.integrations.reqstore.ReqStore
import io.hydrosphere.serving.gateway.execution.Types.{MessageData, ServingReqStore}
import io.hydrosphere.serving.gateway.util.CircuitBreaker
import io.hydrosphere.serving.monitoring.api.ExecutionInformation
import io.hydrosphere.serving.monitoring.api.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.metadata.{ApplicationInfo, ExecutionError, ExecutionMetadata}
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}

import scala.concurrent.duration._

trait MonitorExec[F[_]] {
  def monitor(
    request: MessageData,
    response: AssociatedResponse,
    appInfo: Option[ApplicationInfo]
  ): F[ExecutionMetadata]
}

object MonitorExec {
  def make[F[_]](
    monitoring: Monitoring[F],
    maybeReqStore: Option[ServingReqStore[F]]
  )(implicit F: Concurrent[F], timer: Timer[F]): MonitorExec[F] = {
    (request: MessageData, response: AssociatedResponse, appInfo: Option[ApplicationInfo]) => {
      val mv = response.servable.modelVersion
      val wrappedRequest = PredictRequest(
        inputs = request,
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
      for {
        maybeTraceData <- maybeReqStore.toOptionT[F].flatMap { rs =>
          val res = CircuitBreaker[F](3 seconds, 5, 30 seconds).use {
            rs.save(mv.id.toString, wrappedRequest -> wrappedResponse).attempt.map(_.toOption)
          }
          OptionT(res)
        }.value
        execMeta = ExecutionMetadata(
          signatureName = mv.predict.signatureName,
          modelVersionId = mv.id,
          modelName = mv.name,
          modelVersion = mv.version,
          traceData = maybeTraceData,
          requestId = "",
          appInfo = appInfo,
          latency = response.resp.metadata.latency
        )
        execInfo = ExecutionInformation(
          request = Option(wrappedRequest),
          metadata = Option(execMeta),
          responseOrError = wrappedResponse
        )
        _ <- monitoring.send(execInfo)
      } yield execMeta
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