package io.hydrosphere.serving.gateway

import java.util.concurrent.TimeUnit

import cats.Traverse
import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Async, IO, Resource, Sync}
import io.grpc.{CallOptions, ClientCall, ManagedChannel, ManagedChannelBuilder, MethodDescriptor}
import io.hydrosphere.serving.gateway.util.AsyncUtil
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc

import scala.concurrent.ExecutionContext
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.option._
import cats.implicits._
import io.hydrosphere.serving.gateway.persistence.application.StoredStage
import io.hydrosphere.serving.manager.grpc.entities.ModelVersion
import fs2.Stream
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.PredictionExecutor.ServableFactory
import io.hydrosphere.serving.gateway.config.Configuration
import io.hydrosphere.serving.gateway.api.grpc.Prediction.ServingReqStore
import io.hydrosphere.serving.gateway.integrations.Monitoring
import io.hydrosphere.serving.gateway.integrations.reqstore.ReqStore
import io.hydrosphere.serving.gateway.persistence.StoredStage
import io.hydrosphere.serving.monitoring.api.ExecutionInformation
import io.hydrosphere.serving.monitoring.api.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.metadata.{ApplicationInfo, ExecutionError, ExecutionMetadata}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.tensor.StringTensor

import scala.util.Random


object PredictionExecutor {
  type PredictionExecutor[F[_]] = PredictRequest => F[PredictResponse]
  type ChannelFactory[F[_]] = (String, Int) => F[ManagedChannel]
  type ServableFactory[F[_]] = StoredServable => F[PredictionExecutor[F]]

  def grpcChannel[F[_]](implicit F: Sync[F]) = { (host: String, port: Int) =>
    Resource.make(F.delay(ManagedChannelBuilder.forAddress(host, port).build()))(c => F.delay(c.shutdownNow()))
  }

  def forServable[F[_]](
    servable: StoredServable,
    channelCtor: ChannelFactory[F]
  )(
    implicit F: Async[F],
    ec: ExecutionContext
  ) = {
    for {
      channel <- channelCtor(servable.host, servable.port)
      stub = PredictionServiceGrpc.stub(channel)
    } yield { data: PredictRequest =>
      AsyncUtil.futureAsync {
        stub.predict(data)
      }
    }
  }

  def stageStream[F[_]](
    stage: StoredStage,
    servableCtor: ServableFactory[F]
  )(implicit F: Async[F]) = {
    val downstream = Stream.emits(stage.servables.toList)
      .evalMap(x => servableCtor(x).map(y => x -> y))
    val x = { data: PredictRequest =>
      downstream.map {
        case (servable, predictor) => servable -> predictor(data)
      }
    }
    x
  }

  def randomSelector[F[_]](
    stages: Stream[F, (StoredServable, ExecutionMetadata, PredictResponse)]
  )(
    implicit F: Sync[F],
    rng: RandomNumberGenerator[F]
  ) = {
    for {
      random <- rng.getInt(100)
      res <- stages.filter(_._1.weight <= random).compile.lastOrError
    } yield res
  }

  def monitor[F[_]](
    monitoring: Monitoring[F],
    maybeReqStore: Option[ServingReqStore[F]]
  )(implicit F: Sync[F]) = {
    def forReq(request: PredictRequest, appInfo: Option[ApplicationInfo])
      (servable: StoredServable, res: Either[Throwable, PredictResponse]) = {
      val roe = res match {
        case Left(err) => ExecutionInformation.ResponseOrError.Error(ExecutionError(err.toString))
        case Right(value) => ExecutionInformation.ResponseOrError.Response(value)
      }
      val mv = servable.modelVersion
      for {
        maybeTraceData <- maybeReqStore.toOptionT[F].flatMap(rs =>
          OptionT.liftF(rs.save("asd", request -> roe))
        ).value
        execMeta = ExecutionMetadata(
          signatureName = mv.contract.flatMap(_.predict.map(_.signatureName)).getOrElse("<unknown>"),
          modelVersionId = mv.id,
          modelName = mv.model.map(_.name).getOrElse("<unknown>"),
          modelVersion = mv.version,
          traceData = maybeTraceData,
          requestId = "",
          appInfo = appInfo
        )
        execInfo = ExecutionInformation(
          request = Option(request),
          metadata = Option(execMeta),
          responseOrError = roe
        )
        _ <- monitoring.send(execInfo)
      } yield execMeta
    }

    forReq _
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

class ExecutionTests extends GenericTest {
  describe("Basic Servable executor") {
    it("should bl") {
      val monitoring = new Monitoring[IO] {
        override def send(execInfo: ExecutionInformation): IO[Unit] = IO.unit
      }
      val rqstore = PredictionExecutor.mkReqStore[IO](???).unsafeRunSync().get
      val servableFactory: ServableFactory[IO] = { _ =>
        IO.pure { s =>
          IO.pure(PredictResponse(s.inputs))
        }
      }


      val stage = StoredStage("1", NonEmptyList.of(StoredServable("AYAYA", 420, 100, modelVersion = ModelVersion(id = 42))), ModelSignature.defaultInstance)
      val stream = PredictionExecutor.stageStream[IO](stage, servableFactory)
      val request = PredictRequest(
        modelSpec = None,
        inputs = Map(
          "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
        )
      )
      val monitor = PredictionExecutor.monitor(monitoring, None)
      val reqMon = monitor(request, None)
      val results = stream(request)
        .evalMap {
          case (s, res) => res.attempt
            .flatMap(x => reqMon(s, x))
            .flatMap(execMeta => res.map(x => Tuple3(s, execMeta, x)))
        }

      implicit val rng = RandomNumberGenerator.default[IO].unsafeRunSync()
      val output = PredictionExecutor.randomSelector(results)

      results.compile.toList.unsafeToFuture().map { fr =>
        assert(fr.nonEmpty)
      }
    }

    it("should execute") {
      def dummyChannelCtor[IO](_host: String, _port: Int) = IO {
        new ManagedChannel {
          override def shutdown(): ManagedChannel = ???

          override def isShutdown: Boolean = ???

          override def isTerminated: Boolean = ???

          override def shutdownNow(): ManagedChannel = ???

          override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = ???

          override def newCall[RequestT, ResponseT](methodDescriptor: MethodDescriptor[RequestT, ResponseT], callOptions: CallOptions): ClientCall[RequestT, ResponseT] = ???

          override def authority(): String = ???
        }
      }

      val servalbe = StoredServable("AYAYA", 420, 100, modelVersion = ModelVersion(id = 42))
      val testResult = for {
        servableExecutor <- PredictionExecutor.forServable(servalbe, dummyChannelCtor)
        result <- servableExecutor(PredictRequest())
      } yield result
      testResult.unsafeToFuture().map { res =>
        assert(res.outputs.nonEmpty)
      }
    }
  }
}
