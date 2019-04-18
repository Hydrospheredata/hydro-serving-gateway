package io.hydrosphere.serving.gateway.service.application

import cats.effect.Sync
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.api.http.controllers.{JsonServeByIdRequest, JsonServeByNameRequest}
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import spray.json.JsValue

import scala.concurrent.ExecutionContext


trait ApplicationExecutionService[F[_]] {
  def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest): F[JsValue]

  def serveJsonById(jsonServeRequest: JsonServeByIdRequest): F[JsValue]

  def serveProtoRequest(data: PredictRequest): F[PredictResponse]

  def listApps: F[List[StoredApplication]]
}

object ApplicationExecutionService {
  def makeDefault[F[_]](appConfig: ApplicationConfig, appStorage: ApplicationStorage[F], prediction: Prediction[F])
    (implicit F: Sync[F], ec: ExecutionContext) = F.delay {
    new ApplicationExecutionServiceImpl[F](appConfig, appStorage, prediction)
  }
}