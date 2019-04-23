package io.hydrosphere.serving.gateway.execution.servable

import java.time.Instant

import cats.Functor
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.MessageData
import io.hydrosphere.serving.gateway.util.InstantClock

final case class ServableRequest(
  data: MessageData,
  timestamp: Instant,
  requestId: Option[String] = None
)

object ServableRequest {
  def forNow[F[_]](data: MessageData)(implicit F: Functor[F], clock: InstantClock[F]): F[ServableRequest] = {
    for {
      time <- clock.now
    } yield ServableRequest(data, time, None)
  }
}

final case class ServableResponse(
  data: Either[Throwable, MessageData],
  latency: Double
)
