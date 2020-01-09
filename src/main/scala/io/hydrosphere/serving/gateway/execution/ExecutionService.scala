package io.hydrosphere.serving.gateway.execution

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.execution.Types.MessageData
import io.hydrosphere.serving.gateway.execution.servable.ServableRequest
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import io.hydrosphere.serving.gateway.util.UUIDGenerator
import io.hydrosphere.serving.monitoring.metadata.TraceData
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}


/**
  * Facade for any kind of execution.
  *
  * Handles both Servables and Applications.
  */
trait ExecutionService[F[_]] {
  def predict(data: PredictRequest): F[PredictResponse]

  def predictWithoutShadow(data: PredictRequest): F[PredictResponse]

  def predictServable(data: MessageData, name: String): F[PredictResponse]

  def replay(data: PredictRequest, time: Option[TraceData]): F[PredictResponse]
}

object ExecutionService {
  def makeDefault[F[_]](
    appStorage: ApplicationStorage[F],
    servableStorage: ServableStorage[F]
  )(
    implicit F: Sync[F],
    uuid: UUIDGenerator[F]
  ): F[ExecutionService[F]] = F.delay {
    new ExecutionService[F] {

      override def predict(data: PredictRequest): F[PredictResponse] = {
        for {
          res <- replay(data, None)
        } yield res
      }

      override def replay(data: PredictRequest, time: Option[TraceData]): F[PredictResponse] = {
        for {
          modelSpec <- F.fromOption(data.modelSpec, GatewayError.InvalidArgument("ModelSpec is not defined"))
          executor <- OptionT(servableStorage.getShadowedExecutor(modelSpec.name))
            .orElseF(appStorage.getExecutor(modelSpec.name))
            .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find servable ${modelSpec.name}")))
          id <- uuid.random.map(_.toString)
          request = ServableRequest(
            data = data.inputs,
            replayTrace = time,
            requestId = id
          )
          res <- executor.predict(request)
          responseData <- F.fromEither(res.data)
        } yield PredictResponse(responseData)
      }

      override def predictWithoutShadow(data: PredictRequest): F[PredictResponse] = {
        for {
          modelSpec <- F.fromOption(data.modelSpec, GatewayError.InvalidArgument("ModelSpec is not defined"))
          servable <- OptionT(servableStorage.getExecutor(modelSpec.name))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Executor $modelSpec is not found")))
          id <- uuid.random.map(_.toString)
          request = ServableRequest(
            data = data.inputs,
            replayTrace = None,
            requestId = id
          )
          res <- servable.predict(request)
          responseData <- F.fromEither(res.data)
        } yield PredictResponse(responseData)
      }

      override def predictServable(data: MessageData, name: String): F[PredictResponse] = {
        for {
          servable <- OptionT(servableStorage.getShadowedExecutor(name))
            .getOrElseF(F.raiseError(GatewayError.NotFound(s"Servable $name not found")))
          id <- uuid.random.map(_.toString)
          res <- servable.predict(ServableRequest(data, id))
          response <- F.fromEither(res.data)
        } yield PredictResponse(response)
      }
    }
  }
}