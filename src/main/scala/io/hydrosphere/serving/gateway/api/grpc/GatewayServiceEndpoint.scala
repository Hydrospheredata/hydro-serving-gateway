package io.hydrosphere.serving.gateway.api.grpc

import cats.data.OptionT
import cats.effect.Effect
import cats.effect.implicits._
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.api.GatewayServiceGrpc.GatewayService
import io.hydrosphere.serving.gateway.api.{ReplayRequest, ServablePredictRequest}
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.monitoring.metadata.TraceData
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future

class GatewayServiceEndpoint[F[_]](
  executor: ExecutionService[F]
)(implicit F: Effect[F]) extends GatewayService with Logging {
  override def shadowlessPredict(request: PredictRequest): Future[PredictResponse] = {
    logger.info(s"Got shadowless request from GRPC. modelSpec=${request.modelSpec}")
    executor.predictWithoutShadow(request)
      .attempt
      .map {
        case Right(result) =>
          logger.info("Returning successful GRPC response")
          result.asRight
        case Left(error) =>
          logger.warn("Returning failed GRPC response", error)
          error.asLeft
      }
      .rethrow.toIO.unsafeToFuture()
  }

  override def replayModel(request: ReplayRequest): Future[PredictResponse] = {
    logger.info(s"Got replay request from GRPC. modelSpec=${request.spec}")
    val result = for {
      resultTuple <- OptionT.fromOption[F](GatewayServiceEndpoint.replayToPredict(request))
        .getOrElseF(F.raiseError(GatewayError.InvalidArgument("Invalid ReplayRequest")))
      (req, time) = resultTuple
      res <- executor.replay(req, time.some)
    } yield res

    result
      .attempt
      .map {
        case Right(res) =>
          logger.info("Returning successful GRPC replay response")
          res.asRight
        case Left(error) =>
          logger.warn("Returning failed GRPC replay response", error)
          error.asLeft
      }
      .rethrow.toIO.unsafeToFuture()
  }

  override def predictServable(request: ServablePredictRequest): Future[PredictResponse] = {
    logger.info(s"Got servable predict request. servable=${request.servableName}")
    val flow = for {
      resp <- executor.predictServable(request.data, request.servableName)
    } yield resp
    flow.toIO.unsafeToFuture()
  }
}

object GatewayServiceEndpoint {
  def replayToPredict(replayRequest: ReplayRequest): Option[(PredictRequest, TraceData)] = {
    replayRequest.originTd.map { time =>
      PredictRequest(replayRequest.spec, replayRequest.data) -> time
    }
  }
}