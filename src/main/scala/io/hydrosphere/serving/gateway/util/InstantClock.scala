package io.hydrosphere.serving.gateway.util

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.Functor
import cats.implicits._
import cats.effect.Clock


trait InstantClock[F[_]] {
  def now: F[Instant]
}

object InstantClock {
  implicit def fromClock[F[_] : Functor](clock: Clock[F]): InstantClock[F] = new InstantClock[F] {
    override def now: F[Instant] = {
      for {
        time <- clock.monotonic(TimeUnit.MILLISECONDS)
      } yield Instant.ofEpochMilli(time)
    }
  }
}