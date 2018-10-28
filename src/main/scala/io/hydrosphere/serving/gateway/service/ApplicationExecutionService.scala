package io.hydrosphere.serving.gateway.service

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import cats.data.EitherT
import cats.implicits._
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Header, Headers}
import io.hydrosphere.serving.model.api.{HFResult, Result, TensorUtil}
import io.hydrosphere.serving.model.api.Result.Implicits._
import io.hydrosphere.serving.model.api.Result.ClientError
import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.monitoring.data_profile_types.DataProfileType
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionError, ExecutionInformation, ExecutionMetadata, MonitoringServiceGrpc}
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, TypedTensorFactory}
import io.hydrosphere.serving.tensorflow.tensor_shape.TensorShapeProto
import io.hydrosphere.serving.tensorflow.types.DataType
import org.apache.logging.log4j.scala.Logging
import spray.json.{JsObject, JsValue}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

trait ApplicationExecutionService {
  def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[JsValue]

  def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[JsValue]

  def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse]

  def listApps: List[GWApplication]
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


class ApplicationExecutionServiceImpl(
  applicationConfig: ApplicationConfig,
  applicationStorage: ApplicationStorageImpl,
  predictGrpcClient: PredictionServiceGrpc.PredictionServiceStub,
  profilerGrpcClient: DataProfilerServiceGrpc.DataProfilerServiceStub,
  monitoringGrpcClient: MonitoringServiceGrpc.MonitoringServiceStub
)(implicit val ex: ExecutionContext) extends ApplicationExecutionService with Logging {

  def listApps: List[GWApplication] = {
    applicationStorage.listAll
  }

  override def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    data.modelSpec match {
      case Some(modelSpec) =>
        applicationStorage.get(modelSpec.name) match {
          case Right(app) =>
            serveApplication(app, data, tracingInfo)
          case Left(error) =>
            Result.errorF(error)
        }
      case None => Future.failed(new IllegalArgumentException("ModelSpec is not defined"))
    }
  }

  override def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[JsObject] = {
    applicationStorage.get(jsonServeRequest.targetId) match {
      case Right(application) =>
        val signature = application.contract.signatures
          .find(_.signatureName == jsonServeRequest.signatureName)
          .toHResult(
            ClientError(s"Application ${jsonServeRequest.targetId} doesn't have a ${jsonServeRequest.signatureName} signature")
          )

        val ds = signature.right.map { sig =>
          new SignatureBuilder(sig).convert(jsonServeRequest.inputs).right.map { tensors =>
            PredictRequest(
              modelSpec = Some(
                ModelSpec(
                  name = application.name,
                  signatureName = jsonServeRequest.signatureName,
                  version = None
                )
              ),
              inputs = tensors.mapValues(_.toProto)
            )
          }
        }

        ds match {
          case Left(err) => Result.errorF(err)
          case Right(Left(tensorError)) => Result.clientErrorF(s"Tensor validation error: $tensorError")
          case Right(Right(request)) =>
            serveApplication(application, request, tracingInfo).map { result =>
              result.right.map(responseToJsObject)
            }
        }
      case Left(error) =>
        Result.errorF(error)
    }
  }

  override def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[JsValue] = {
    val f = for {
      app <- EitherT(Future.apply(applicationStorage.get(jsonServeByNameRequest.appName)))
      serveByIdRequest = jsonServeByNameRequest.toIdRequest(app.id)
      result <- EitherT(serveJsonById(serveByIdRequest, tracingInfo))
    } yield result
    f.value
  }

  def serve(unit: ExecutionUnit, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    val verificationResults = request.inputs.map {
      case (name, tensor) => name -> TensorUtil.verifyShape(tensor)
    }

    val errors = verificationResults.filter {
      case (_, t) => t.isLeft
    }.mapValues(_.left.get)

    if (errors.isEmpty) {
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

      val verifiedInputs = verificationResults.mapValues(_.right.get)
      val verifiedRequest = request.copy(inputs = verifiedInputs)

      requestBuilder
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
            Result.ok(response)
          },
          thr => {
            logger.error("Request to model failed", thr)
            sendToDebug(ResponseOrError.Error(ExecutionError(thr.toString)), verifiedRequest, getCurrentExecutionUnit(unit, modelVersionIdHeaderValue))
            thr
          }
        )
    } else {
      Future.successful(
        Result.errors(
          errors.map {
            case (name, err) =>
              ClientError(s"Shape verification error for input $name: $err")
          }.toSeq
        )
      )
    }
  }

  def servePipeline(units: Seq[ExecutionUnit], data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    //TODO Add request id for step
    val empty = Result.okF(PredictResponse(outputs = data.inputs))
    units.foldLeft(empty) {
      case (a, b) =>
        EitherT(a).flatMap { resp =>
          val request = PredictRequest(
            modelSpec = Some(
              ModelSpec(
                signatureName = b.servicePath
              )
            ),
            inputs = resp.outputs
          )
          EitherT(serve(b, request, tracingInfo))
        }.value
    }
  }

  def serveApplication(application: GWApplication, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
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