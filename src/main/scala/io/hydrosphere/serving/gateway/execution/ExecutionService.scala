package io.hydrosphere.serving.gateway.execution

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.execution.servable.ServableExec
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}


/**
  * Facade for any kind of execution.
  *
  * Handles both Servables and Applications.
  * @tparam F effectful type
  */
trait ExecutionService[F[_]] {
  def serve(data: PredictRequest): F[PredictResponse]

  def selectPredictor(spec: ModelSpec): F[ServableExec[F]]
}

object ExecutionService {
  def makeDefault[F[_]](
    appStorage: ApplicationStorage[F],
    servableStorage: ServableStorage[F]
  )(implicit F: Sync[F]): F[ExecutionService[F]] = F.delay {
    new ExecutionService[F] {

      override def serve(data: PredictRequest): F[PredictResponse] = {
        for {
          modelSpec <- F.fromOption(data.modelSpec, GatewayError.InvalidArgument("ModelSpec is not defined"))
          validated <- F.fromEither(RequestValidator.verify(data)
            .left.map(errs => GatewayError.InvalidArgument(s"Invalid request: ${errs.mkString}")))
          executor <- selectPredictor(modelSpec)
          res <- executor.predict(validated.inputs)
          responseData <- F.fromEither(res.data)
        } yield PredictResponse(responseData)
      }

      override def selectPredictor(spec: ModelSpec): F[ServableExec[F]] = {
        spec.version match {
          case Some(version) =>
            OptionT(servableStorage.getExecutor(spec.name, version))
              .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find servable with name ${spec.name} and version $version")))
          case None =>
            OptionT(appStorage.getExecutor(spec.name))
            .getOrElseF(F.raiseError(GatewayError.NotFound(s"Can't find application with name ${spec.name}")))
        }
      }
    }
  }
}