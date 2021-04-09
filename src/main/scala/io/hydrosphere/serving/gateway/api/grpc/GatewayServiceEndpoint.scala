package io.hydrosphere.serving.gateway.api.grpc

import cats.data.OptionT
import cats.effect.Effect
import cats.effect.implicits._
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.proto.gateway.api.GatewayServiceGrpc.GatewayService
import io.hydrosphere.serving.proto.gateway.api.GatewayPredictRequest
import io.hydrosphere.serving.proto.runtime.api.PredictResponse
import io.hydrosphere.serving.gateway.execution.ExecutionService
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future

class GatewayServiceEndpoint[F[_]](
  executor: ExecutionService[F]
)(implicit F: Effect[F]) extends GatewayService with Logging {

  override def predictServable(request: GatewayPredictRequest): Future[PredictResponse] = {
    logger.debug(s"Got servable predict request, servable=${request.name}")
    executor.predictServable(request).toIO.unsafeToFuture()
  }

  override def shadowlessPredictServable(request: GatewayPredictRequest): Future[PredictResponse] = {
    logger.debug(s"Got shadowless request from, servable=${request.name}")
    executor.predictShadowlessServable(request)
      .attempt
      .map {
        case Right(result) =>
          logger.debug("Returning successful shadowless servable predict response")
          result.asRight
        case Left(error) =>
          logger.warn("Returning failed shadowless servable predict  response", error)
          error.asLeft
      }
      .rethrow.toIO.unsafeToFuture()
  }

  override def predictApplication(request: GatewayPredictRequest): Future[PredictResponse] = {
    logger.debug(s"Got application predict request, application=${request.name}")
    executor.predictApplication(request)
      .attempt
      .map {
        case Right(result) =>
          logger.debug("Returning successful application predict response")
          result.asRight
        case Left(error) =>
          logger.warn("Returning failed application predict response", error)
          error.asLeft
      }
      .rethrow.toIO.unsafeToFuture()
  }

}
