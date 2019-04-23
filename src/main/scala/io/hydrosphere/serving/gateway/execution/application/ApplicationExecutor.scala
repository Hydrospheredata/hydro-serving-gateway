package io.hydrosphere.serving.gateway.execution.application

import cats.Traverse
import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{Async, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.ServableCtor
import io.hydrosphere.serving.gateway.execution.servable.{ServableExec, ServableRequest, ServableResponse}
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.gateway.util.InstantClock

object ApplicationExecutor {

  def pipelineExecutor[F[_]](
    stages: NonEmptyList[ServableExec[F]],
  )(implicit F: Sync[F], clock: InstantClock[F]): ServableExec[F] = {
    val pipelinedExecs = stages.map { x =>
      Kleisli { data: (ServableResponse, ServableRequest) =>
        for {
          time <- clock.now
          lastData <- F.fromEither(data._1.data)
          req = ServableRequest(
            data = lastData,
            timestamp = time,
            requestId = data._2.requestId
          )
          res <- x.predict(req)
        } yield (res, req)
      }
    }

    val pipeline = pipelinedExecs.tail.foldLeft(pipelinedExecs.head) {
      case (a, b) => a.andThen(b)
    }

    data: ServableRequest => {
      val initData = ServableResponse(data.data.asRight, 0) -> data
      for {
        result <- pipeline.run(initData)
      } yield result._1
    }
  }

  def appExecutor[F[_]](
    app: StoredApplication,
    shadow: MonitorExec[F],
    servableFactory: ServableCtor[F],
    rng: ResponseSelector[F]
  )(implicit F: Async[F], clock: InstantClock[F]): F[ServableExec[F]] = {
    for {
      stagesFunc <- Traverse[List].traverse(app.stages.toList) { stage =>
        StageExec.withShadow(app, stage, servableFactory, shadow, rng)
      }
      nonEmptyFuncs <- F.fromOption(
        NonEmptyList.fromList(stagesFunc),
        new IllegalStateException(s"Application with no stages id=${app.id}, name=${app.name}")
      )
    } yield pipelineExecutor(nonEmptyFuncs)
  }
}
