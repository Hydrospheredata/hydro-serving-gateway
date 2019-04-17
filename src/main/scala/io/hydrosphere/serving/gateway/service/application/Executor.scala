package io.hydrosphere.serving.gateway.service.application

import java.util.concurrent.TimeUnit

import cats._
import cats.data.{Kleisli, NonEmptyList, OptionT}
import cats.effect.{Async, Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.config.Configuration
import io.hydrosphere.serving.gateway.integrations.Monitoring
import io.hydrosphere.serving.gateway.integrations.Prediction.ServingReqStore
import io.hydrosphere.serving.gateway.integrations.reqstore.ReqStore
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication, StoredStage}
import io.hydrosphere.serving.gateway.persistence.servable.StoredServable
import io.hydrosphere.serving.gateway.service.application.PredictionExecutor.{MessageData, ServableFactory}
import io.hydrosphere.serving.gateway.util.AsyncUtil
import io.hydrosphere.serving.model.api.TensorUtil
import io.hydrosphere.serving.monitoring.api.ExecutionInformation
import io.hydrosphere.serving.monitoring.api.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.metadata.{ApplicationInfo, ExecutionError, ExecutionMetadata}
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.TensorProto

import scala.concurrent.ExecutionContext
import scala.util.Random

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

trait ResponseSelector[F[_]] {
  def chooseOne(resps: List[AssociatedResponse]): F[AssociatedResponse]
}

object ResponseSelector {
  // TODO(bulat) make better routing
  def randomSelector[F[_]](
    implicit F: Sync[F],
    rng: RandomNumberGenerator[F]
  ): ResponseSelector[F] = {
    resps: List[AssociatedResponse] => {
      for {
        random <- rng.getInt(100)
        res <- F.fromOption(
          resps.find(_.servable.weight <= random).orElse(resps.headOption),
          new AssertionError("Stage without servables")
        )
      } yield res
    }
  }
}

trait ServableExec[F[_]] {
  def predict(data: MessageData): F[ServableResponse]
}

object ServableExec {
  def forServable[F[_]](
    servable: StoredServable,
    channelCtor: ChannelFactory[F]
  )(
    implicit F: Async[F],
    clock: Clock[F],
    ec: ExecutionContext
  ): F[ServableExec[F]] = {
    for {
      stub <- channelCtor.make(servable.host, servable.port)
        .use(channel => F.delay(PredictionServiceGrpc.stub(channel)))
    } yield new ServableExec[F] {
      def predict(data: MessageData): F[ServableResponse] = {
        val req = PredictRequest(
          modelSpec = Some(ModelSpec(
            name = servable.modelVersion.model.map(_.name).getOrElse(""),
            version = servable.modelVersion.version.some,
            signatureName = servable.modelVersion.contract.flatMap(_.predict.map(_.signatureName)).getOrElse("")
          )),
          inputs = data
        )
        for {
          start <- clock.monotonic(TimeUnit.MILLISECONDS)
          res <- AsyncUtil.futureAsync {
            stub.predict(req)
          }.attempt
          end <- clock.monotonic(TimeUnit.MILLISECONDS)
        } yield ServableResponse(
          data = res.map(_.outputs),
          metadata = ResponseMetadata(
            latency = end - start
          )
        )
      }
    }
  }
}

object StageExec {
  def withShadow[F[_]](
    stage: StoredStage,
    servableCtor: ServableFactory[F],
    shadow: Monitoring[F],
    selector: ResponseSelector[F]
  )(implicit F: Async[F]): F[ServableExec[F]] = {
    for {
      downstream <- Traverse[List].traverse(stage.services.toList)(x => servableCtor(x).map(y => x -> y))
    } yield {
      new ServableExec[F] {
        def predict(data: MessageData) = {
          for {
            stageRes <- Traverse[List].traverse(downstream) {
              case (servable, predictor) => predictor.predict(data).map{ resp =>
                AssociatedResponse(resp, servable)
              }
            }
            next <- selector.chooseOne(stageRes)
          } yield next
       }
      }
    }
  }

}

trait MonitorExec[F[_]] {
  def monitor(
    request: MessageData,
    response: Either[Throwable, MessageData],
    servable: StoredServable,
    appInfo: Option[ApplicationInfo]
  ): F[ExecutionMetadata]
}

object MonitorExec {
  def make[F[_]](
    monitoring: Monitoring[F],
    maybeReqStore: Option[ServingReqStore[F]]
  )(implicit F: Sync[F]) = {
    new MonitorExec[F] {
      override def monitor(
        request: MessageData,
        response: Either[Throwable, MessageData],
        servable: StoredServable,
        appInfo: Option[ApplicationInfo]
      ): F[ExecutionMetadata] = {
        val wrappedRequest = PredictRequest(
          //          modelSpec=???, // TODO fill the modelSpec
          inputs = request
        )
        val wrappedResponse = response match {
          case Left(err) => ExecutionInformation.ResponseOrError.Error(ExecutionError(err.toString))
          case Right(value) => ExecutionInformation.ResponseOrError.Response(PredictResponse(value))
        }
        val mv = servable.modelVersion
        for {
          maybeTraceData <- maybeReqStore.toOptionT[F].flatMap(rs =>
            OptionT.liftF(rs.save("asd", wrappedRequest -> wrappedResponse))
          ).value
          execMeta = ExecutionMetadata(
            signatureName = mv.contract.flatMap(_.predict.map(_.signatureName)).getOrElse("<unknown>"),
            modelVersionId = mv.id,
            modelName = mv.model.map(_.name).getOrElse("<unknown>"),
            modelVersion = mv.version,
            traceData = maybeTraceData,
            requestId = "",
            appInfo = appInfo,
            latency = 0
          )
          execInfo = ExecutionInformation(
            request = Option(wrappedRequest),
            metadata = Option(execMeta),
            responseOrError = wrappedResponse
          )
          _ <- monitoring.send(execInfo)
        } yield execMeta
      }
    }
  }

  def mkReqStore[F[_]](conf: Configuration)(implicit F: Async[F]): F[Option[ServingReqStore[F]]] = {
    if (conf.application.reqstore.enabled) {
      ReqStore.create[F, (PredictRequest, ResponseOrError)](conf.application.reqstore)
        .map(_.some)
    } else {
      F.pure(None)
    }
  }
}

object PredictionExecutor {
  type MessageData = Map[String, TensorProto]
  type ServableFactory[F[_]] = StoredServable => F[ServableExec[F]]

  def pipelineExecutor[F[_]](
    stages: NonEmptyList[ServableExec[F]]
  )(implicit F: Sync[F]): ServableExec[F] = {
    val pipelinedExecs = stages.map { x =>
      Kleisli { data: ServableResponse =>
        for {
          goodData <- F.fromEither(data.data)
          res <- x.predict(goodData)
        } yield res
      }
    }
    val pipeline = pipelinedExecs.tail.foldLeft(pipelinedExecs.head) {
      case (a, b) => a >> b
    }
    data: MessageData => pipeline.run(ServableResponse(data = Right(data), metadata = ResponseMetadata(0)))
  }

  def appExecutor[F[_]](
    app: StoredApplication,
    shadow: Monitoring[F],
    servableFactory: ServableFactory[F],
    rng: ResponseSelector[F]
  )(implicit F: Async[F]) = {
    for {
      stagesFunc <- Traverse[List].traverse(app.stages.toList) { stage =>
        StageExec.withShadow(stage, servableFactory, shadow, rng)
      }
      nonEmptyFuncs <- F.fromOption(
        NonEmptyList.fromList(stagesFunc),
        new IllegalStateException(s"Application with no stages id=${app.id}, name=${app.name}")
      )
    } yield pipelineExecutor(nonEmptyFuncs)
  }





}

trait RandomNumberGenerator[F[_]] {
  def getInt(max: Int): F[Int]
}

object RandomNumberGenerator {
  def default[F[_]](implicit F: Sync[F]) = F.delay {
    val random = new Random()
    new RandomNumberGenerator[F] {
      override def getInt(max: Int): F[Int] = F.delay(random.nextInt(max))
    }
  }

  def withSeed[F[_]](seed: Long)(implicit F: Sync[F]) = F.delay {
    val random = new Random()
    new RandomNumberGenerator[F] {
      override def getInt(max: Int): F[Int] = F.delay(random.nextInt(max))
    }
  }
}

class Executor[F[_]](
  appStorage: ApplicationStorage[F],
  servableFactory: ServableFactory[F],
  monitor: MonitorExec[F],
)(implicit F: Async[F]) {

  def serve(request: PredictRequest): F[PredictResponse] = {
    for {
      spec <- OptionT.fromOption(request.modelSpec).getOrElseF(
        F.raiseError(GatewayError.InvalidArgument("modelSpec field is not present in the request"))
      )
      response <- spec.version match {
        case Some(version) => serveModelVersion(spec.name, version, request.inputs)
        case None => serveApp(spec.name, request)
      }
    } yield response
  }

  def serveApp(appName: String, data: PredictRequest): F[Map[String, TensorProto]] = {
    for {
      app <- OptionT(appStorage.getByName(appName)).getOrElseF(
        F.raiseError(GatewayError.NotFound(s"Can't find application with name=$appName"))
      )
      verifiedData <- F.fromEither(verify(data.inputs))
      appExecutor = PredictionExecutor.appExecutor(app, monitor, servableFactory, rng)
      result <- appExecutor(PredictRequest(data.modelSpec, verifiedData))
    } yield result
  }

  def serveModelVersion(modelName: String, version: Long, data: Map[String, TensorProto]): F[Map[String, TensorProto]] = ???

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
