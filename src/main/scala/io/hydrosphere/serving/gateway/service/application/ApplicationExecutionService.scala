package io.hydrosphere.serving.gateway.service.application

import java.util.concurrent.atomic.AtomicReference

import cats.data.EitherT
import cats.effect.IO
import cats.implicits._
import cats.{ApplicativeError, MonadError}
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication}
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Headers}
import io.hydrosphere.serving.model.api.Result.{ClientError, ErrorCollection}
import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.model.api.{HFResult, Result, TensorUtil}
import io.hydrosphere.serving.monitoring.data_profile_types.DataProfileType
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionError, ExecutionInformation, ExecutionMetadata, MonitoringServiceGrpc}
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, TypedTensorFactory}
import io.hydrosphere.serving.tensorflow.tensor_shape.TensorShapeProto
import io.hydrosphere.serving.tensorflow.types.DataType
import org.apache.logging.log4j.scala.Logging
import spray.json.{JsObject, JsValue}

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

trait ApplicationExecutionService[F[_]] {
  def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue]

  def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue]

  def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse]

  def listApps: F[Seq[StoredApplication]]
}

private case class ExecutionUnit(
  serviceName: String,
  servicePath: String,
  stageInfo: StageInfo,
)

private case class StageInfo(
  applicationRequestId: Option[String],
  signatureName: String,
  applicationId: Long,
  modelVersionId: Option[Long],
  stageId: String,
  applicationNamespace: Option[String],
  dataProfileFields: Map[String, DataProfileType] = Map.empty
)

object Kek {
  type AP[T[_]] = MonadError[T, Throwable]
}

class ApplicationExecutionServiceImpl[F[_]: Kek.AP](
  applicationConfig: ApplicationConfig,
  applicationStorage: ApplicationStorage[F],
  predictGrpcClient: PredictionServiceGrpc.PredictionServiceStub,
  profilerGrpcClient: DataProfilerServiceGrpc.DataProfilerServiceStub,
  monitoringGrpcClient: MonitoringServiceGrpc.MonitoringServiceStub
)(implicit val ex: ExecutionContext) extends ApplicationExecutionService[F] with Logging {

  private[this] val ME = MonadError[F, Throwable]

  def listApps: F[Seq[StoredApplication]] = {
    applicationStorage.listAll
  }

  def getApp(name: String): F[StoredApplication] = {
    for {
      maybeApp <- applicationStorage.get(name)
      app <- ME.fromOption(maybeApp, new RuntimeException(s"Can't find an app with name $name"))
    } yield app
  }

  def getApp(id: Long): F[StoredApplication] = {
    for {
      maybeApp <- applicationStorage.get(id)
      app <- ME.fromOption(maybeApp, new RuntimeException(s"Can't find an app with id $id"))
    } yield app
  }

  override def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    data.modelSpec match {
      case Some(modelSpec) =>
        for {
          app <- getApp(modelSpec.name)
          result <- serveApplication(app, data, tracingInfo)
        } yield result
      case None => ApplicativeError[F, Throwable].raiseError(new IllegalArgumentException("ModelSpec is not defined"))
    }
  }

  def jsonToRequest(appName: String, inputs: JsObject, signanture: ModelSignature): Try[PredictRequest] = {
    val convertResult = new SignatureBuilder(signanture).convert(inputs)
    convertResult match {
      case Left(value) =>
        Failure(new IllegalArgumentException(value.message))
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
  }

  override def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue] = {
    for {
      app <- getApp(jsonServeRequest.targetId)
      maybeSignature = app.contract.signatures.find(_.signatureName == jsonServeRequest.signatureName)
      signature <- ME.fromOption(maybeSignature, new RuntimeException(s"Can't find a signature ${jsonServeRequest.signatureName}"))
      maybeRequest = jsonToRequest(app.name, jsonServeRequest.inputs, signature)
      request <- ME.fromTry(maybeRequest)
      result <- serveApplication(app, request, tracingInfo)
    } yield responseToJsObject(result)
  }

  override def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue] = {
    for {
      app <- getApp(jsonServeByNameRequest.appName)
      maybeSignature = app.contract.signatures.find(_.signatureName == jsonServeByNameRequest.signatureName)
      signature <- ME.fromOption(maybeSignature, new RuntimeException(s"Can't find a signature ${jsonServeByNameRequest.signatureName}"))
      maybeRequest = jsonToRequest(app.name, jsonServeByNameRequest.inputs, signature)
      request <- ME.fromTry(maybeRequest)
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

  def serve(unit: ExecutionUnit, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): IO[PredictResponse] = {
    verify(request) match {
      case Left(tensorErrors) =>
        IO.raiseError(new IllegalArgumentException(
          ErrorCollection(
            tensorErrors.map {
              case (name, err) =>
                ClientError(s"Shape verification error for input $name: $err")
            }.toSeq
          ).toString
        ))
      case Right(verifiedRequest) =>
        val modelVersionIdHeaderValue = new AtomicReference[String](null)
        val latencyHeaderValue = new AtomicReference[String](null)

        val deadline = applicationConfig.grpc.deadline

        var requestBuilder = predictGrpcClient
          .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, unit.serviceName)
          .withOption(Headers.XServingModelVersionId.callOptionsClientResponseWrapperKey, modelVersionIdHeaderValue)
          .withOption(Headers.XEnvoyUpstreamServiceTime.callOptionsClientResponseWrapperKey, latencyHeaderValue)
          .withDeadlineAfter(deadline.length, deadline.unit)

        if (tracingInfo.isDefined) {
          val tr = tracingInfo.get
          requestBuilder = requestBuilder
            .withOption(Headers.XRequestId.callOptionsKey, tr.xRequestId)

          if (tr.xB3requestId.isDefined) {
            requestBuilder = requestBuilder
              .withOption(Headers.XB3TraceId.callOptionsKey, tr.xB3requestId.get)
          }

          if (tr.xB3SpanId.isDefined) {
            requestBuilder = requestBuilder
              .withOption(Headers.XB3ParentSpanId.callOptionsKey, tr.xB3SpanId.get)
          }
        }

        def fResult = requestBuilder
          .predict(verifiedRequest)
          .transform(
            response => {
              try {
                val latency = getLatency(latencyHeaderValue)
                val res = if (latency.isSuccess) {
                  response.addInternalInfo(
                    "system.latency" -> latency.get
                  )
                } else {
                  response
                }

                sendToDebug(ResponseOrError.Response(res), verifiedRequest, getCurrentExecutionUnit(unit, modelVersionIdHeaderValue))
              } catch {
                case NonFatal(e) => logger.error("Error while transforming the response", e)
              }
              response
            },
            thr => {
              logger.error("Request to model failed", thr)
              sendToDebug(ResponseOrError.Error(ExecutionError(thr.toString)), verifiedRequest, getCurrentExecutionUnit(unit, modelVersionIdHeaderValue))
              thr
            }
          )

        IO.fromFuture(IO(fResult))
    }
  }

  def servePipeline(units: Seq[ExecutionUnit], data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    //TODO Add request id for step
    val empty = IO.pure(PredictResponse(outputs = data.inputs))
    units.foldLeft(empty) {
      case (a, b) =>
        a.flatMap { res =>
          val request = PredictRequest(
            modelSpec = Some(
              ModelSpec(
                signatureName = b.servicePath
              )
            ),
            inputs = res.outputs
          )
          serve(b, request, tracingInfo)
        }
    }
  }

  def serveApplication(application: StoredApplication, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): IO[PredictResponse] = {
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
            serve(unit, request, tracingInfo)
          case None => Result.clientErrorF("ModelSpec in request is not specified")
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
                Result.ok(
                  ExecutionUnit(
                    serviceName = stage.id,
                    //servicePath = stage.services.head.signature.get.signatureName, // FIXME dirty hack to fix service signatures
                    servicePath = signature.signatureName,
                    stageInfo = stageInfo
                  )
                )
              case None => Result.clientError(s"$stage doesn't have a signature")
            }
        }

        Result.sequence(execUnits) match {
          case Left(err) => Result.errorF(err)
          case Right(units) =>
            servePipeline(units, request, tracingInfo)
        }
    }
  }


  private def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
    JsObject(fields)
  }

  private def sendToDebug(responseOrError: ResponseOrError, predictRequest: PredictRequest, executionUnit: ExecutionUnit): Unit = {
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
      val deadline = applicationConfig.grpc.deadline

      //TODO do we really need this?
      monitoringGrpcClient
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, applicationConfig.monitoringDestination)
        .withDeadlineAfter(deadline.length, deadline.unit)
        .analyze(execInfo)
        .onComplete {
          case Failure(thr) =>
            logger.warn("Can't send message to the monitoring service", thr)
          case _ =>
            Unit
        }

      profilerGrpcClient
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, applicationConfig.profilingDestination)
        .withDeadlineAfter(deadline.length, deadline.unit)
        .analyze(execInfo)
        .onComplete {
          case Failure(thr) =>
            logger.warn("Can't send message to the data profiler service", thr)
          case _ => Unit
        }
    }
  }

  private def getCurrentExecutionUnit(unit: ExecutionUnit, modelVersionIdHeaderValue: AtomicReference[String]): ExecutionUnit = Try({
    Option(modelVersionIdHeaderValue.get()).map(_.toLong)
  }).map(s => unit.copy(stageInfo = unit.stageInfo.copy(modelVersionId = s)))
    .getOrElse(unit)


  private def getLatency(latencyHeaderValue: AtomicReference[String]): Try[TensorProto] = {
    Try({
      Option(latencyHeaderValue.get()).map(_.toLong)
    }).map(v => TensorProto(
      dtype = DataType.DT_INT64,
      int64Val = Seq(v.getOrElse(0)),
      tensorShape = Some(TensorShapeProto(dim = Seq(TensorShapeProto.Dim(1))))
    ))
  }


}