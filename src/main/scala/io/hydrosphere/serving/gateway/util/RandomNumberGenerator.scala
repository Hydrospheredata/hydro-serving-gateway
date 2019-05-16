package io.hydrosphere.serving.gateway.util

import java.util.Random

import cats.effect.Sync

trait RandomNumberGenerator[F[_]] {
  def getInt(max: Int): F[Int]
}

object RandomNumberGenerator {
  def default[F[_]](implicit F: Sync[F]): F[RandomNumberGenerator[F]] = F.delay {
    val random = new Random()
    max: Int => F.delay(random.nextInt(max))
  }

  def withSeed[F[_]](seed: Long)(implicit F: Sync[F]): F[RandomNumberGenerator[F]] = F.delay {
    val random = new Random()
    max: Int => F.delay(random.nextInt(max))
  }
}