package io.hydrosphere.serving.gateway.util

import java.util.UUID

import cats.effect.Sync

trait UUIDGenerator[F[_]] {
  def random: F[UUID]
}

object UUIDGenerator {
  def apply[F[_]](implicit F: Sync[F]): UUIDGenerator[F] = new UUIDGenerator[F] {
    override def random: F[UUID] = F.delay {
      UUID.randomUUID()
    }
  }
}