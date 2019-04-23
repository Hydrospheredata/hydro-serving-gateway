package io.hydrosphere.serving.gateway.execution.application

import cats.effect.Async
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.{MessageData, ServableCtor}
import io.hydrosphere.serving.gateway.execution.servable.{ServableExec, ServableRequest, ServableResponse}
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredStage}
import io.hydrosphere.serving.monitoring.metadata.ApplicationInfo

object StageExec {
  def withShadow[F[_]](
    app: StoredApplication,
    stage: StoredStage,
    servableCtor: ServableCtor[F],
    shadow: MonitorExec[F],
    selector: ResponseSelector[F]
  )(implicit F: Async[F]): F[ServableExec[F]] = {
    for {
      downstream <- stage.servables.traverse(x => servableCtor(x).map(y => x -> y))
    } yield {
      new ServableExec[F] {
        def predict(request: ServableRequest): F[ServableResponse] = {
          for {
            stageRes <- downstream.traverse {
              case (servable, predictor) => predictor.predict(request).map { resp =>
                AssociatedResponse(resp, servable)
              }
            }
            _ <- stageRes.traverse { assocResp =>
              shadow.monitor(request, assocResp, Some(ApplicationInfo(app.id, stage.id)))
            }
            next <- selector.chooseOne(stageRes)
          } yield next.resp
        }
      }
    }
  }
}
