package io.hydrosphere.serving.gateway.execution.application

import cats.effect.Concurrent
import cats.effect.implicits._
import cats.implicits._
import io.hydrosphere.serving.gateway.Logging
import io.hydrosphere.serving.gateway.execution.Types.ServableCtor
import io.hydrosphere.serving.gateway.execution.servable.{ServableExec, ServableRequest, ServableResponse}
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredStage}
import io.hydrosphere.serving.monitoring.metadata.{ApplicationInfo, ExecutionMetadata}

object StageExec extends Logging {
  def withShadow[F[_]](
    app: StoredApplication,
    stage: StoredStage,
    servableCtor: ServableCtor[F],
    shadow: MonitorExec[F],
    selector: ResponseSelector[F]
  )(implicit F: Concurrent[F]): F[ServableExec[F]] = {
    for {
      downstream <- stage.servables.traverse(x => servableCtor(x).map(y => x -> y))
    } yield {
      new ServableExec[F] {
        def predict(request: ServableRequest): F[ServableResponse] = {
          for {
            results <- downstream.traverse {
              case (servable, predictor) =>
                val flow = for {
                  res <- predictor.predict(request)
                    .map(AssociatedResponse(_, servable))
                  _ <- shadow.monitor(request, res, Some(ApplicationInfo(app.id, stage.id)))
                    .recover { case x =>
                      logger.error("Error sending monitoring data", x)
                      ExecutionMetadata.defaultInstance
                    }
                } yield res
                flow.start
            }
            stageRes <- results.traverse(_.join)
            next <- selector.chooseOne(stageRes)
          } yield next.resp
        }
      }
    }
  }
}