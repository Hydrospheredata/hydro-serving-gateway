package io.hydrosphere.serving.gateway.service.application

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Async, Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredServable, StoredStage}
import io.hydrosphere.serving.gateway.service.application.Types.{MessageData, ServableCtor}
import io.hydrosphere.serving.model.api.TensorUtil
import io.hydrosphere.serving.monitoring.metadata.ApplicationInfo
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.TensorProto

final case class ResponseMetadata(
  latency: Double
)

final case class ServableResponse(
  data: Either[Throwable, Map[String, TensorProto]],
  metadata: ResponseMetadata
)

case class AssociatedResponse(
  resp: ServableResponse,
  servable: StoredServable,
)

trait ServableExec[F[_]] {
  def predict(data: MessageData): F[ServableResponse]
}

trait CloseableExec[F[_]] extends ServableExec[F] {
  def close: F[Unit]
}

object ServableExec {
  def forServable[F[_]](
    servable: StoredServable,
    clientCtor: PredictionClientFactory[F]
  )(
    implicit F: Sync[F],
    clock: Clock[F],
  ): F[CloseableExec[F]] = {
    for {
      stub <- clientCtor.make(servable.host, servable.port)
    } yield new CloseableExec[F] {
      def predict(data: MessageData): F[ServableResponse] = {
        val req = PredictRequest(
          modelSpec = Some(ModelSpec(
            name = servable.modelVersion.name,
            version = servable.modelVersion.version.some,
            signatureName = servable.modelVersion.predict.signatureName
          )),
          inputs = data
        )
        for {
          start <- clock.monotonic(TimeUnit.MILLISECONDS)
          res <- stub.predict(req).attempt
          end <- clock.monotonic(TimeUnit.MILLISECONDS)
        } yield ServableResponse(
          data = res.map(_.outputs),
          metadata = ResponseMetadata(
            latency = end - start
          )
        )
      }

      override def close: F[Unit] = stub.close()
    }
  }
}

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
        def predict(data: MessageData): F[ServableResponse] = {
          for {
            stageRes <- downstream.traverse {
              case (servable, predictor) => predictor.predict(data).map { resp =>
                AssociatedResponse(resp, servable)
              }
            }
            _ <- stageRes.traverse { assocResp =>
              shadow.monitor(data, assocResp, Some(ApplicationInfo(app.id, stage.id)))
            }
            next <- selector.chooseOne(stageRes)
          } yield next
        }
      }
    }
  }
}

class Executor[F[_]](
  appStorage: ApplicationStorage[F],
  servableFactory: ServableCtor[F],
  monitor: MonitorExec[F],
)(implicit F: Async[F]) {

  def serve(request: PredictRequest): F[PredictResponse] = {
    for {
      spec <- OptionT.fromOption(request.modelSpec).getOrElseF(
        F.raiseError(GatewayError.InvalidArgument("modelSpec field is not present in the request"))
      )
      response <- spec.version match {
        case Some(version) => F.delay(???) // serveModelVersion(spec.name, version, request.inputs)
        case None => F.delay(???) // serveApp(spec.name, request)
      }
    } yield response
  }

//  def serveApp(appName: String, data: PredictRequest): F[Map[String, TensorProto]] = {
//    for {
//      app <- OptionT(appStorage.getByName(appName)).getOrElseF(
//        F.raiseError(GatewayError.NotFound(s"Can't find application with name=$appName"))
//      )
//      verifiedData <- F.fromEither(verify(data.inputs))
//      appExecutor = PredictionExecutor.appExecutor(app, monitor, servableFactory)
//      result <- appExecutor(PredictRequest(data.modelSpec, verifiedData))
//    } yield result
//  }
//
//  def serveModelVersion(modelName: String, version: Long, data: Map[String, TensorProto]): F[Map[String, TensorProto]] = ???

  def verify(data: Map[String, TensorProto]): Either[GatewayError, Map[String, TensorProto]] = {
    val verificationResults = data.map {
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
      Right(verifiedInputs)
    } else {
      Left(GatewayError.InvalidArgument(
        "Invalid request. " + errors.mkString("\n")
      ))
    }
  }
}