package io.hydrosphere.serving.gateway.execution.grpc

import cats.effect.Sync
import cats.implicits._
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

trait GrpcChannel[F[_]] {
  def close(): F[Unit]
  def underlying: ManagedChannel
}

object GrpcChannel {

  trait Factory[F[_]] {
    def make(host: String, port: Int): F[GrpcChannel[F]]
  }

  def grpc[F[_]](implicit F: Sync[F]): GrpcChannel.Factory[F] = {
    (host: String, port: Int) => {
      for {
        ch <- F.delay {
          val builder = ManagedChannelBuilder.forAddress(host, port)
          builder.usePlaintext()
          builder.enableRetry()
          builder.build()
        }
      } yield new GrpcChannel[F] {
        override def close(): F[Unit] = F.delay(ch.shutdown())

        override def underlying: ManagedChannel = ch
      }
    }
  }
}