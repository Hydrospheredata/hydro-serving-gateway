package io.hydrosphere.serving.gateway.execution.application

import cats.data.OptionT
import cats.effect.{Async, Concurrent, Timer}
import cats.implicits._
import io.hydrosphere.serving.gateway.Logging
import io.hydrosphere.serving.gateway.execution.servable.ServableRequest
import io.hydrosphere.serving.gateway.integrations.Monitoring
import io.hydrosphere.serving.gateway.persistence.StoredModelVersion
import io.hydrosphere.monitoring.proto.sonar.entities.ExecutionInformation
import io.hydrosphere.monitoring.proto.sonar.entities.{ExecutionMetadata, ApplicationInfo}
import io.hydrosphere.serving.proto.runtime.api.{PredictRequest, PredictResponse}

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
    appInfo: Option[ApplicationInfo],
    latency: Double,
    requestId: String
  ) = {
    ExecutionMetadata(
      signatureName = modelVersion.signature.signatureName,
      modelVersionId = modelVersion.id,
      modelName = modelVersion.name,
      modelVersion = modelVersion.version,
      requestId = requestId,
      appInfo = appInfo,
      latency = latency,
    )
  }

  def make[F[_]](
    monitoring: Monitoring[F],
  )(implicit F: Concurrent[F], timer: Timer[F]): MonitoringClient[F] = new MonitoringClient[F] {
    override def monitor(request: ServableRequest, response: AssociatedResponse, appInfo: Option[ApplicationInfo]): F[ExecutionMetadata] = {
      val mv = response.servable.modelVersion
      val wrappedRequest = PredictRequest(request.data)
      val wrappedResponseOrError = response.resp.data match {
        case Left(err) => ExecutionInformation.ResponseOrError.Error(err.toString)
        case Right(value) => ExecutionInformation.ResponseOrError.Response(PredictResponse(value))
      }
      val meta = mkExecutionMetadata(
          mv,
          appInfo,
          response.resp.latency,
          request.requestId
        )
      val flow = for {
        _ <- Logging.debug(s"Monitoring response for ${response.servable} ${appInfo}")
        execInfo = ExecutionInformation(request = wrappedRequest.some, responseOrError = wrappedResponseOrError, metadata = meta.some)
        _ <- monitoring.send(execInfo)
      } yield meta

      flow.handleErrorWith { error =>
        F.delay {
          logger.error("Can't send data to shadow.", error)
          meta
        }
      }
    }
  }
}