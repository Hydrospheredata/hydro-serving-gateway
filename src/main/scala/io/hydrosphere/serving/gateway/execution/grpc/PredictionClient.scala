package io.hydrosphere.serving.gateway.execution.grpc

import cats.effect.Async
import cats.implicits._
import io.hydrosphere.serving.gateway.util.AsyncUtil
import io.hydrosphere.serving.proto.runtime.api.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.proto.runtime.api.PredictionServiceGrpc

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/***
  * Interface for low-level GRPC communication
  * @tparam F
  */
trait PredictionClient[F[_]] {
  def predict(request: PredictRequest): F[PredictResponse]
  def close(): F[Unit]
}

object PredictionClient {

  trait Factory[F[_]] {
    def make(host: String, port: Int): F[PredictionClient[F]]
  }

  def forEc[F[_]](
    ec: ExecutionContext,
    channelCtor: GrpcChannel.Factory[F],
    callDeadline: Duration,
    maxMessageSize: Int
  )(implicit F: Async[F]): Factory[F] = {
    implicit val executionContext: ExecutionContext = ec
    (host: String, port: Int) => {
      for {
        channel <- channelCtor.make(host, port)
        s <- F.delay(
          PredictionServiceGrpc.stub(channel.underlying)
            .withMaxInboundMessageSize(maxMessageSize)
            .withMaxOutboundMessageSize(maxMessageSize)
        )
      } yield new PredictionClient[F] {
        override def predict(request: PredictRequest): F[PredictResponse] = AsyncUtil.futureAsync {
          s.withDeadlineAfter(callDeadline._1, callDeadline._2).predict(request)
        }

        override def close(): F[Unit] = F.delay(channel.close())
      }
    }
  }
}