package io.hydrosphere.serving.gateway.service.application

import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Traverse}
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.{InvalidArgument, NotFound}
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.grpc.PredictionServiceAlg
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication}
import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.model.api.{Result, TensorUtil}
import io.hydrosphere.serving.monitoring.data_profile_types.DataProfileType
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionError, ExecutionInformation, ExecutionMetadata}
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.TypedTensorFactory
import org.apache.logging.log4j.scala.Logging
import spray.json.{JsObject, JsValue}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

trait ApplicationExecutionService[F[_]] {
  def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue]

  def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue]

  def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse]

  def listApps: F[Seq[StoredApplication]]
}

case class ExecutionUnit(
  serviceName: String,
  servicePath: String,
  stageInfo: StageInfo,
)

case class StageInfo(
  applicationRequestId: Option[String],
  signatureName: String,
  applicationId: Long,
  modelVersionId: Option[Long],
  stageId: String,
  applicationNamespace: Option[String],
  dataProfileFields: Map[String, DataProfileType] = Map.empty
)

class ApplicationExecutionServiceImpl[F[_]: Sync](
  applicationConfig: ApplicationConfig,
  applicationStorage: ApplicationStorage[F],
  predictionServiceAlg: PredictionServiceAlg[F],
)(implicit val ex: ExecutionContext) extends ApplicationExecutionService[F] with Logging {

  def listApps: F[Seq[StoredApplication]] = {
    applicationStorage.listAll
  }

  def getApp(name: String): F[StoredApplication] = {
    for {
      maybeApp <- applicationStorage.get(name)
      app <- Sync[F].fromOption(maybeApp, NotFound(s"Can't find an app with name $name"))
    } yield app
  }

  def getApp(id: Long): F[StoredApplication] = {
    for {
      maybeApp <- applicationStorage.get(id)
      app <- Sync[F].fromOption(maybeApp, NotFound(s"Can't find an app with id $id"))
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
        Sync[F].raiseError(InvalidArgument("ModelSpec is not defined"))
    }
  }

  def jsonToRequest(appName: String, inputs: JsObject, signanture: ModelSignature): Try[PredictRequest] = {

    try {
      val c = new SignatureBuilder(signanture).convert(inputs)
      c match {
        case Left(value) =>
          Failure(InvalidArgument(value.message))
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
      case x: Throwable => Failure(InvalidArgument(x.getMessage))
    }
  }

  override def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue] = {
    for {
      app <- getApp(jsonServeRequest.targetId)
      maybeSignature = app.contract.signatures.find(_.signatureName == jsonServeRequest.signatureName)
      signature <- Sync[F].fromOption(maybeSignature, NotFound(s"Can't find a signature ${jsonServeRequest.signatureName}"))
      maybeRequest = jsonToRequest(app.name, jsonServeRequest.inputs, signature)
      request <- Sync[F].fromTry(maybeRequest)
      result <- serveApplication(app, request, tracingInfo)
    } yield responseToJsObject(result)
  }

  override def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue] = {
    for {
      app <- getApp(jsonServeByNameRequest.appName)
      maybeSignature = app.contract.signatures.find(_.signatureName == jsonServeByNameRequest.signatureName)
      signature <- Sync[F].fromOption(maybeSignature, NotFound(s"Can't find a signature ${jsonServeByNameRequest.signatureName}"))
      maybeRequest = jsonToRequest(app.name, jsonServeByNameRequest.inputs, signature)
      request <- Sync[F].fromTry(maybeRequest)
      result <- serveApplication(app, request, tracingInfo)
    } yield responseToJsObject(result)
  }

  def verify(request: PredictRequest): Either[Map[String, Result.HError], PredictRequest] = {
    val verificationResults = request.inputs.map {
      case (name, tensor) =>
        val verifiedTensor = if (!tensor.tensorContent.isEmpty) { // tensorContent - byte field, thus skip verifications
          Right(tensor)
        } else {
          TensorUtil.verifyShape(tensor)
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

  def sendToDebug(responseOrError: ResponseOrError, predictRequest: PredictRequest, executionUnit: ExecutionUnit) = {
    if (applicationConfig.shadowingOn) {
      val execInfo = ExecutionInformation(
        metadata = Option(ExecutionMetadata(
          applicationId = executionUnit.stageInfo.applicationId,
          stageId = executionUnit.stageInfo.stageId,
          modelVersionId = executionUnit.stageInfo.modelVersionId.getOrElse(-1),
          signatureName = executionUnit.stageInfo.signatureName,
          applicationRequestId = executionUnit.stageInfo.applicationRequestId.getOrElse(""),
          requestId = executionUnit.stageInfo.applicationRequestId.getOrElse(""), //todo fetch from response,
          applicationNamespace = executionUnit.stageInfo.applicationNamespace.getOrElse(""),
          dataTypes = executionUnit.stageInfo.dataProfileFields
        )),
        request = Option(predictRequest),
        responseOrError = responseOrError
      )
      for {
        _ <- predictionServiceAlg.sendToMonitoring(execInfo)
        _ <- predictionServiceAlg.sendToProfiling(execInfo)
      } yield ()
    } else {
      Applicative[F].pure(())
    }
  }

  def servePipeline(units: Seq[ExecutionUnit], data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    //TODO Add request id for step
    val empty = Applicative[F].pure(PredictResponse(outputs = data.inputs))
    units.foldLeft(empty) {
      case (previous, current) =>
        previous.flatMap { res =>
          val request = PredictRequest(
            modelSpec = ModelSpec(signatureName = current.servicePath).some,
            inputs = res.outputs
          )
          predictionServiceAlg.sendToExecUnit(current, request, tracingInfo).attempt.flatMap {
            case Right(value) =>
              sendToDebug(ResponseOrError.Response(value), request, current)
              Applicative[F].pure(value)
            case Left(error) =>
              sendToDebug(ResponseOrError.Error(ExecutionError(error.getMessage)), request, current)
              Sync[F].raiseError(error)
          }
        }
    }
  }

  def serveApplication(application: StoredApplication, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    application.executionGraph.stages match {
      case stage :: Nil if stage.services.lengthCompare(1) == 0 => // single stage with single service
        request.modelSpec match {
          case Some(servicePath) =>
            val modelVersionId = application.executionGraph.stages.headOption.flatMap(
              _.services.headOption.flatMap(_.modelVersionId))

            val stageInfo = StageInfo(
              modelVersionId = modelVersionId,
              applicationRequestId = tracingInfo.map(_.xRequestId), // TODO get real traceId
              applicationId = application.id,
              signatureName = servicePath.signatureName,
              stageId = stage.id,
              applicationNamespace = application.namespace,
              dataProfileFields = stage.dataProfileFields
            )
            val unit = ExecutionUnit(
              serviceName = stage.id,
              servicePath = servicePath.signatureName,
              stageInfo = stageInfo
            )
            servePipeline(Seq(unit), request, tracingInfo)
          case None => Sync[F].raiseError(InvalidArgument("ModelSpec in request is not specified"))
        }
      case stages => // pipeline
        val execUnits = stages.zipWithIndex.map {
          case (stage, idx) =>
            stage.signature match {
              case Some(signature) =>
                val vers = stage.services.headOption.flatMap(_.modelVersionId)
                val stageInfo = StageInfo(
                  //TODO will be wrong modelVersionId during blue-green
                  //TODO Get this value from sidecar or in sidecar
                  modelVersionId = vers,
                  applicationRequestId = tracingInfo.map(_.xRequestId), // TODO get real traceId
                  applicationId = application.id,
                  signatureName = signature.signatureName,
                  stageId = stage.id,
                  applicationNamespace = application.namespace,
                  dataProfileFields = stage.dataProfileFields
                )
                Applicative[F].pure(
                  ExecutionUnit(
                    serviceName = stage.id,
                    //servicePath = stage.services.head.signature.get.signatureName, // FIXME dirty hack to fix service signatures
                    servicePath = signature.signatureName,
                    stageInfo = stageInfo
                  )
                )
              case None =>
                Sync[F].raiseError[ExecutionUnit](NotFound(s"$stage doesn't have a signature"))
            }
        }

        for {
          units <- Traverse[List].sequence(execUnits.toList)
          res <- servePipeline(units, request, tracingInfo)
        } yield res
    }
  }

  private def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
    JsObject(fields)
  }
}