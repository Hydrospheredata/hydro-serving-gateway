package io.hydrosphere.serving.gateway.service.application

import cats.effect.{Resource, Sync}
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

trait ChannelFactory[F[_]] {
  def make(host: String, port: Long): Resource[F, ManagedChannel]
}

object ChannelFactory {
  def grpc[F[_]](implicit F: Sync[F]) = { (host: String, port: Int) =>
    Resource.make(F.delay(ManagedChannelBuilder.forAddress(host, port).build()))(c => F.delay(c.shutdownNow()))
  }
}