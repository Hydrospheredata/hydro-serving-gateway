package io.hydrosphere.serving.gateway.service.application

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import io.hydrosphere.serving.gateway.util.RandomNumberGenerator

trait ResponseSelector[F[_]] {
  def chooseOne(resps: NonEmptyList[AssociatedResponse]): F[AssociatedResponse]
}

object ResponseSelector {
  // TODO(bulat) make better routing
  def randomSelector[F[_]](
    implicit F: Sync[F],
    rng: RandomNumberGenerator[F]
  ): ResponseSelector[F] = {
    resps: NonEmptyList[AssociatedResponse] => {
      for {
        random <- rng.getInt(100)
      } yield resps.find(_.servable.weight <= random).getOrElse(resps.head)
    }
  }
}