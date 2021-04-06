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
import io.hydrosphere.serving.proto.runtime.api.PredictResponse
import io.hydrosphere.serving.proto.gateway.api.GatewayPredictRequest


/**
  * Facade for any kind of execution.
  *
  * Handles both Servables and Applications.
  */
trait ExecutionService[F[_]] {
  def predictShadowlessServable(request: GatewayPredictRequest): F[PredictResponse]

  def predictServable(request: GatewayPredictRequest): F[PredictResponse]

  def predictApplication(request: GatewayPredictRequest): F[PredictResponse]
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

      override def predictShadowlessServable(request: GatewayPredictRequest): F[PredictResponse] = {
        for {
          servable <- OptionT(servableStorage.getExecutor(request.name))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Executor ${request.name} is not found")))
          id <- uuid.random.map(_.toString)
          req = ServableRequest(
            data = request.data,
            requestId = id
          )
          res <- servable.predict(req)
          responseData <- F.fromEither(res.data)
        } yield PredictResponse(responseData)
      }

      override def predictServable(request: GatewayPredictRequest): F[PredictResponse] = {
        for {
          servable <- OptionT(servableStorage.getShadowedExecutor(request.name))
            .getOrElseF(F.raiseError(GatewayError.NotFound(s"Servable ${request.name} not found")))
          id <- uuid.random.map(_.toString)
          res <- servable.predict(ServableRequest(request.data, id))
          response <- F.fromEither(res.data)
        } yield PredictResponse(response)
      }

      override def predictApplication(request: GatewayPredictRequest): F[PredictResponse] = {
        for {
          app <- OptionT(appStorage.getExecutor(request.name))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Application ${request.name} is not found")))
          id <- uuid.random.map(_.toString)
          res <- app.predict(ServableRequest(request.data, id))
          response <- F.fromEither(res.data)
        } yield PredictResponse(response)
      }
    }
  }
}