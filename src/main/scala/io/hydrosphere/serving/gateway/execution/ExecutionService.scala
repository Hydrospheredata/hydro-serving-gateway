package io.hydrosphere.serving.gateway.execution

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.execution.Types.MessageData
import io.hydrosphere.serving.gateway.execution.servable.{Predictor, ServableRequest}
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import io.hydrosphere.serving.gateway.util.UUIDGenerator
import io.hydrosphere.serving.monitoring.metadata.TraceData
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}


/**
  * Facade for any kind of execution.
  *
  * Handles both Servables and Applications.
  * @tparam F effectful type
  */
trait ExecutionService[F[_]] {
  def predict(data: PredictRequest): F[PredictResponse]

  def predictWithoutShadow(data: PredictRequest): F[PredictResponse]

  def predictServable(data: MessageData, name: String): F[PredictResponse]

  def replay(data: PredictRequest, time: Option[TraceData]): F[PredictResponse]

  def selectPredictor(spec: ModelSpec): F[Predictor[F]]
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

      override def selectPredictor(spec: ModelSpec): F[Predictor[F]] = {
        spec.version match {
          case Some(version) =>
            OptionT(servableStorage.getShadowedExecutor(spec.name, version))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find servable with name ${spec.name} and version $version")))
          case None =>
            OptionT(appStorage.getExecutor(spec.name))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find application with name ${spec.name}")))
        }
      }

      override def replay(data: PredictRequest, time: Option[TraceData]): F[PredictResponse] = {
        for {
          modelSpec <- F.fromOption(data.modelSpec, GatewayError.InvalidArgument("ModelSpec is not defined"))
          validated <- F.fromEither(RequestValidator.verify(data.inputs)
            .left.map(errs => GatewayError.InvalidArgument(s"Invalid request: ${errs.mkString}")))
          executor <- selectPredictor(modelSpec)
          id <- uuid.random.map(_.toString)
          request = ServableRequest(
            data = validated,
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
          validated <- F.fromEither(RequestValidator.verify(data.inputs)
            .left.map(errs => GatewayError.InvalidArgument(s"Invalid request: ${errs.mkString}")))
          servable <- shadowlessPredictor(modelSpec)
          id <- uuid.random.map(_.toString)
          request = ServableRequest(
            data = validated,
            replayTrace = None,
            requestId = id
          )
          res <- servable.predict(request)
          responseData <- F.fromEither(res.data)
        } yield PredictResponse(responseData)
      }

      def shadowlessPredictor(spec: ModelSpec): F[Predictor[F]] = {
        spec.version match {
          case Some(version) =>
            OptionT(servableStorage.getExecutor(spec.name, version))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find servable with name ${spec.name} and version $version")))
          case None =>
            F.raiseError(GatewayError.NotSupported("Replay for applications is not supported"))
        }
      }

      override def predictServable(data: MessageData, name: String): F[PredictResponse] = {
        for {
          servable <- OptionT(servableStorage.getExecutor(name))
            .getOrElseF(F.raiseError(GatewayError.NotFound(s"Servable $name not found")))
          id <- uuid.random.map(_.toString)
          res <- servable.predict(ServableRequest(data, id))
          response <- F.fromEither(res.data)
        } yield PredictResponse(response)
      }
    }
  }
}