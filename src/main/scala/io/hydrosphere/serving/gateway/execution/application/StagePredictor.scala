package io.hydrosphere.serving.gateway.execution.application

import cats.effect.Concurrent
import cats.effect.implicits._
import cats.implicits._
import io.hydrosphere.serving.gateway.Logging
import io.hydrosphere.serving.gateway.execution.Types.PredictorCtor
import io.hydrosphere.serving.gateway.execution.servable.{Predictor, ServableRequest, ServableResponse}
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredStage}
import io.hydrosphere.monitoring.proto.sonar.entities.ApplicationInfo

object StagePredictor extends Logging {
  def withShadow[F[_]](
    app: StoredApplication,
    stage: StoredStage,
    servableCtor: PredictorCtor[F],
    shadow: MonitoringClient[F],
    selector: ResponseSelector[F]
  )(implicit F: Concurrent[F]): F[Predictor[F]] = {
    for {
      downstream <- stage.servables.traverse { x =>
        for {
          predictor <- servableCtor(x)
        } yield x -> Predictor.withShadow(x, predictor, shadow, Some(ApplicationInfo(app.name, stage.id)))
      }
    } yield {
      new Predictor[F] {
        def predict(request: ServableRequest): F[ServableResponse] = {
          for {
            results <- downstream.traverse {
              case (s, predictor) =>
                predictor.predict(request)
                  .map(AssociatedResponse(_, s))
                  .start
            }
            stageRes <- results.traverse(_.join)
            next <- selector.chooseOne(stageRes)
          } yield next.resp
        }
      }
    }
  }
}