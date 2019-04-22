package io.hydrosphere.serving.gateway.execution.application

import cats.Traverse
import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{Async, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.gateway.execution.Types.{MessageData, ServableCtor}
import io.hydrosphere.serving.gateway.execution.servable
import io.hydrosphere.serving.gateway.execution.servable.{ResponseMetadata, ServableExec, ServableResponse}

object ApplicationExecutor {

  def pipelineExecutor[F[_]](
    stages: NonEmptyList[ServableExec[F]]
  )(implicit F: Sync[F]): ServableExec[F] = {
    val pipelinedExecs = stages.map { x =>
      Kleisli { data: ServableResponse =>
        for {
          goodData <- F.fromEither(data.data)
          res <- x.predict(goodData)
        } yield res
      }
    }
    val pipeline = pipelinedExecs.tail.foldLeft(pipelinedExecs.head) {
      case (a, b) => a.andThen(b)
    }
    data: MessageData => pipeline.run(servable.ServableResponse(data = Right(data), metadata = ResponseMetadata(0)))
  }

  def appExecutor[F[_]](
    app: StoredApplication,
    shadow: MonitorExec[F],
    servableFactory: ServableCtor[F],
    rng: ResponseSelector[F]
  )(implicit F: Async[F]): F[ServableExec[F]] = {
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
