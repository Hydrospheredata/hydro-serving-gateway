package io.hydrosphere.serving.gateway.execution.application

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.PredictorCtor
import io.hydrosphere.serving.gateway.execution.servable.{Predictor, ServableRequest, ServableResponse}
import io.hydrosphere.serving.gateway.persistence.StoredApplication

object ApplicationExecutor {

  def pipelineExecutor[F[_]](
    stages: NonEmptyList[Predictor[F]]
  )(implicit F: Sync[F]): Predictor[F] = {
    val pipelinedExecs = stages.map { x =>
      Kleisli { data: (ServableResponse, ServableRequest) =>
        for {
          lastData <- F.fromEither(data._1.data)
          req = ServableRequest(
            data = lastData,
            replayTrace = None,
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
    shadow: MonitoringClient[F],
    servableFactory: PredictorCtor[F],
    rng: ResponseSelector[F]
  )(implicit F: Concurrent[F]): F[Predictor[F]] = {
    for {
      stagesFunc <- app.stages.traverse { stage =>
        StagePredictor.withShadow(app, stage, servableFactory, shadow, rng)
      }
    } yield pipelineExecutor(stagesFunc)
  }
}