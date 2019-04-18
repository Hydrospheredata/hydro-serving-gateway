package io.hydrosphere.serving.gateway.service.application

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.api.http.controllers.{JsonServeByIdRequest, JsonServeByNameRequest}
import io.hydrosphere.serving.gateway.integrations.Prediction
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.model.api.TensorUtil
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.TypedTensorFactory
import org.apache.logging.log4j.scala.Logging
import spray.json.{JsObject, JsValue}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}


class ApplicationExecutionServiceImpl[F[_]: Sync](
  applicationConfig: ApplicationConfig,
  applicationStorage: ApplicationStorage[F],
  prediction: Prediction[F],
)(implicit val ex: ExecutionContext) extends ApplicationExecutionService[F] with Logging {

  def listApps: F[List[StoredApplication]] = {
    applicationStorage.listAll
  }

  def getApp(name: String): F[StoredApplication] = {
    for {
      maybeApp <- applicationStorage.getByName(name)
      app <- Sync[F].fromOption(maybeApp, GatewayError.NotFound(s"Can't find an app with name $name"))
    } yield app
  }

  def getApp(id: Long): F[StoredApplication] = {
    for {
      maybeApp <- applicationStorage.getById(id)
      app <- Sync[F].fromOption(maybeApp, GatewayError.NotFound(s"Can't find an app with id $id"))
    } yield app
  }

  override def serveProtoRequest(data: PredictRequest): F[PredictResponse] = {
    data.modelSpec match {
      case Some(modelSpec) =>
        for {
          app <- getApp(modelSpec.name)
          result <- serve(app, data)
        } yield result
      case None =>
        Sync[F].raiseError(GatewayError.InvalidArgument("ModelSpec is not defined"))
    }
  }



  override def serveJsonById(jsonServeRequest: JsonServeByIdRequest): F[JsValue] = {
    for {
      app <- getApp(jsonServeRequest.targetId)
      maybeRequest = jsonToRequest(app.name, jsonServeRequest.inputs, app.signature)
      request <- Sync[F].fromTry(maybeRequest)
      result <- serve(app, request)
    } yield responseToJsObject(result)
  }

  override def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest): F[JsValue] = {
    for {
      app <- getApp(jsonServeByNameRequest.appName)
      maybeRequest = jsonToRequest(app.name, jsonServeByNameRequest.inputs, app.signature)
      request <- Sync[F].fromTry(maybeRequest)
      result <- serve(app, request)
    } yield responseToJsObject(result)
  }

  def verify(request: PredictRequest): Either[Map[String, GatewayError], PredictRequest] = {
    val verificationResults = request.inputs.map {
      case (name, tensor) =>
        val verifiedTensor = if (!tensor.tensorContent.isEmpty) { // tensorContent - byte field, thus skip verifications
          Right(tensor)
        } else {
          Either.fromOption(
            TensorUtil.verifyShape(tensor),
            GatewayError.InvalidTensorShape(s"$name tensor has invalid shape").asInstanceOf[GatewayError]
          )
        }
        name -> verifiedTensor
    }

    val errors = verificationResults.filter {
      case (_, t) => t.isLeft
    }.mapValues(_.left.get)

    if (errors.isEmpty) {
      val verifiedInputs = verificationResults.mapValues(_.right.get)
      Right(request.copy(inputs = verifiedInputs))
    } else {
      Left(errors)
    }
  }

  def servePipeline(units: Seq[ExecutionUnit], data: PredictRequest): F[PredictResponse] = {
    //TODO Add request id for step
    val empty = Applicative[F].pure(PredictResponse(outputs = data.inputs))
    units.foldLeft(empty) {
      case (previous, current) =>
        previous.flatMap { res =>
          val request = PredictRequest(
            modelSpec = ModelSpec(signatureName = current.meta.servicePath).some,
            inputs = res.outputs
          )
          prediction.predict(current, request)
        }
    }
  }

  def serve(application: StoredApplication, request: PredictRequest): F[PredictResponse] = {
    val units = application.stages.map(stage => {
      val meta = ExecutionMeta(
        serviceName = stage.id,
        servicePath = stage.signature.signatureName,
        applicationRequestId = None,
        applicationId = application.id.toLong,
        signatureName = stage.signature.signatureName,
        stageId = stage.id,
        applicationNamespace = application.namespace,
      )
      ExecutionUnit(stage.client, meta)
    })
    servePipeline(units, request)
  }


}