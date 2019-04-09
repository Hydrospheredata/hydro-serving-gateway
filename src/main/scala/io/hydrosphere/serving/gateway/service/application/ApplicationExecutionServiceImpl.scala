package io.hydrosphere.serving.gateway.service.application

import cats.{Applicative, Traverse}
import cats.effect.Sync
import cats.implicits._
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.grpc.Prediction
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication, StoredStage}
import io.hydrosphere.serving.model.api.TensorUtil
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, PredictDownstream, StoredApplication}
import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc.PredictionServiceStub
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

  def listApps: F[Seq[StoredApplication]] = {
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
      maybeApp <- applicationStorage.getById(id.toString)
      app <- Sync[F].fromOption(maybeApp, GatewayError.NotFound(s"Can't find an app with id $id"))
    } yield app
  }

  override def serveGrpcApplication(
    data: PredictRequest,
    tracingInfo: Option[RequestTracingInfo]
  ): F[PredictResponse] = {
    data.modelSpec match {
      case Some(modelSpec) =>
        for {
          app <- getApp(modelSpec.name)
          result <- serveApplication(app, data, tracingInfo)
        } yield result
      case None =>
        Sync[F].raiseError(GatewayError.InvalidArgument("ModelSpec is not defined"))
    }
  }

  def jsonToRequest(appName: String, inputs: JsObject, signanture: ModelSignature): Try[PredictRequest] = {
    try {
      val c = new SignatureBuilder(signanture).convert(inputs)
      c match {
        case Left(value) =>
          Failure(GatewayError.InvalidArgument(s"Validation error: $value"))
        case Right(tensors) =>
          Success(PredictRequest(
            modelSpec = Some(
              ModelSpec(
                name = appName,
                signatureName = signanture.signatureName,
                version = None
              )
            ),
            inputs = tensors.mapValues(_.toProto)
          ))
      }
    } catch {
      case x: Throwable => Failure(GatewayError.InvalidArgument(x.getMessage))
    }
  }

  override def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue] = {
    for {
      app <- getApp(jsonServeRequest.targetId)
      signature <- Sync[F].fromOption(
        app.contract.predict,
        GatewayError.NotFound(s"Tried to access invalid application. Empty contract.")
      )
      maybeRequest = jsonToRequest(app.name, jsonServeRequest.inputs, signature)
      request <- Sync[F].fromTry(maybeRequest)
      result <- serveApplication(app, request, tracingInfo)
    } yield responseToJsObject(result)
  }

  override def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue] = {
    for {
      app <- getApp(jsonServeByNameRequest.appName)
      signature <- Sync[F].fromOption(
        app.contract.predict,
        GatewayError.NotFound(s"Tried to access invalid application. Empty contract.")
      )
      maybeRequest = jsonToRequest(app.name, jsonServeByNameRequest.inputs, signature)
      request <- Sync[F].fromTry(maybeRequest)
      result <- serveApplication(app, request, tracingInfo)
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

  def predictConsecutiveUnits(units: Seq[ExecutionUnit], data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    //TODO Add request id for step
    val empty = Applicative[F].pure(PredictResponse(outputs = data.inputs))
    units.foldLeft(empty) {
      case (previous, current) =>
        previous.flatMap { res =>
          val request = PredictRequest(
            modelSpec = ModelSpec(signatureName = current.meta.servicePath).some,
            inputs = res.outputs
          )
          prediction.predict(current, request, tracingInfo).map(_.response)
        }
    }
  }

  def servePipeline(units: Seq[ExecutionUnit], data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    //TODO Add request id for step
    val empty = Applicative[F].pure(PredictResponse(outputs = data.inputs))
    units.foldLeft(empty) {
      case (previous, current) =>
        previous.flatMap { res =>
          val request = PredictRequest(
            modelSpec = ModelSpec(signatureName = current.meta.servicePath).some,
            inputs = res.outputs
          )
          prediction.predict(current, request, tracingInfo).map(_.response)
        }
    }
  }

  def serveApplication(application: StoredApplication, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    val units = application.stages.map(stage => {
      val meta = ExecutionMeta(
        serviceName = stage.id,
        servicePath = stage.signature.signatureName,
        applicationRequestId = tracingInfo.map(_.xRequestId), // TODO get real traceId
        applicationId = application.id.toLong, // TODO
        signatureName = stage.signature.signatureName,
        stageId = stage.id,
        applicationNamespace = application.namespace,
        modelVersionId = stage.services.map(_.modelVersion.id).head // FIXME ubrat eto ebuchee govno otsuda
      )
      ExecutionUnit(stage.client, meta)
    })
    servePipeline(units, request, tracingInfo)
  }

  private def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
    JsObject(fields)
  }
}