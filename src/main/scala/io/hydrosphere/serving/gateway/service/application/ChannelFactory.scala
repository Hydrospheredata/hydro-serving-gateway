package io.hydrosphere.serving.gateway.service.application

import cats.effect.{Async, Sync}
import cats.implicits._
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import io.hydrosphere.serving.gateway.util.AsyncUtil
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

trait GrpcChannel[F[_]] {
  def close(): F[Unit]
  def underlying: ManagedChannel
}

trait ChannelFactory[F[_]] {
  def make(host: String, port: Int): F[ManagedChannel]
}

object ChannelFactory {
  def grpc[F[_]](implicit F: Sync[F]): ChannelFactory[F] = {
    (host: String, port: Int) => {
      for {
        ch <- F.delay(ManagedChannelBuilder.forAddress(host, port).build())
      } yield new GrpcChannel[F] {
        override def close(): F[Unit] = F.delay(ch.shutdown())

        override def underlying: ManagedChannel = ch
      }
    }
  }
}

trait PredictionClient[F[_]] {
  def predict(request: PredictRequest): F[PredictResponse]
  def close(): F[Unit]
}

trait PredictionClientFactory[F[_]] {
  def make(host: String, port: Int): F[PredictionClient[F]]
}

object PredictionClientFactory {
  def forEc[F[_]](
    ec: ExecutionContext,
    channelCtor: ChannelFactory[F],
    callDeadline: Duration,
    maxMessageSize: Int
  )(implicit F: Async[F]): PredictionClientFactory[F] = {
    new PredictionClientFactory[F] {
      implicit val executionContext: ExecutionContext = ec

      override def make(host: String, port: Int): F[PredictionClient[F]] = {
        for {
          channel <- channelCtor.make(host, port)
          s <- F.delay(
            PredictionServiceGrpc.stub(channel)
              .withMaxInboundMessageSize(maxMessageSize)
              .withMaxOutboundMessageSize(maxMessageSize)
          )
        } yield new PredictionClient[F] {
          override def predict(request: PredictRequest): F[PredictResponse] = AsyncUtil.futureAsync {
            s.withDeadlineAfter(callDeadline._1, callDeadline._2).predict(request)
          }

          override def close(): F[Unit] = close()
        }
      }
    }
  }
}