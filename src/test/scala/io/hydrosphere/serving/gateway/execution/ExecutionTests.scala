package io.hydrosphere.serving.gateway.execution

import cats.data.NonEmptyList
import cats.effect.{Clock, IO}
import cats.implicits._
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.GenericTest
import io.hydrosphere.serving.gateway.execution.application.{ApplicationExecutor, AssociatedResponse, MonitorExec, ResponseSelector, StageExec}
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.execution.servable.{ServableExec, ServableRequest, ServableResponse}
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredModelVersion, StoredServable, StoredStage}
import io.hydrosphere.serving.gateway.util.InstantClock
import io.hydrosphere.serving.monitoring.metadata.{ApplicationInfo, ExecutionMetadata}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.{Int64Tensor, StringTensor}
import io.hydrosphere.serving.gateway.util.ShowInstances._

import scala.collection.mutable

class ExecutionTests extends GenericTest {
  describe("Executors") {
    implicit val clock = Clock.create[IO]
    implicit val iclock = InstantClock.fromClock(clock)

    it("should bl") {
      val clientCtor = new PredictionClient.Factory[IO] {
        override def make(host: String, port: Int): IO[PredictionClient[IO]] = IO {
          new PredictionClient[IO] {
            override def predict(request: PredictRequest): IO[PredictResponse] = IO {
              PredictResponse(request.inputs + ("dummy" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto))
            }

            override def close(): IO[Unit] = IO.unit
          }
        }
      }

      val servable = StoredServable("AYAYA", 420, 100, StoredModelVersion(42, 1, "test", ModelSignature.defaultInstance, "Ok"))
      val exec = ServableExec.forServable(servable, clientCtor).unsafeRunSync()
      val requestData = Map(
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      )
      val request = ServableRequest.forNow[IO](requestData).unsafeRunSync()
      val response = exec.predict(request).unsafeRunSync()
      assert(response.data.isRight, response.data)
      val data = response.data.right.get
      assert(data.contains("dummy"))
    }

    it("Servable with shadowing execution") {
      ???
    }

    it("Servable execution with overridden date") {
      ???
    }

    it("Stage execution") {
      val servable1 = StoredServable("A", 420, 100, StoredModelVersion(1, 1, "test", ModelSignature.defaultInstance, "Ok"))
      val servable2 = StoredServable("AY", 420, 100, StoredModelVersion(2, 2, "test", ModelSignature.defaultInstance, "Ok"))
      val servable3 = StoredServable("AYA", 420, 100, StoredModelVersion(3, 3, "test", ModelSignature.defaultInstance, "Ok"))
      val storedStage = StoredStage("test-stage", NonEmptyList.of(servable1, servable2, servable3), ModelSignature.defaultInstance)
      val storedApp = StoredApplication(1, "test-application", None, ModelSignature.defaultInstance, NonEmptyList.of(storedStage))

      val servableCtor: Types.ServableCtor[IO] = { x =>
        IO {
          _: ServableRequest =>
            IO {
              ServableResponse(
                data = Map(
                  "model" -> StringTensor(TensorShape.scalar, Seq(x.modelVersion.name)).toProto,
                  "version" -> Int64Tensor(TensorShape.scalar, Seq(x.modelVersion.version)).toProto
                ).asRight,
                latency = 0
              )
            }
        }
      }
      val shadow: MonitorExec[IO] = (_: ServableRequest, _: AssociatedResponse, _: Option[ApplicationInfo]) => {
        IO(ExecutionMetadata.defaultInstance)
      }
      val selector = new ResponseSelector[IO] {
        override def chooseOne(resps: NonEmptyList[AssociatedResponse]): IO[AssociatedResponse] = {
          IO(resps.head)
        }
      }

      val stageExec = StageExec.withShadow(storedApp, storedStage, servableCtor, shadow, selector).unsafeRunSync()

      val requestData = Map(
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      )
      val request = ServableRequest.forNow[IO](requestData).unsafeRunSync()
      val result = stageExec.predict(request).unsafeRunSync()
      assert(result.data.isRight, result.data)
      val resultData = result.data.right.get
      println(resultData)
      assert(StringTensor.fromProto(resultData("model")).data === Seq("test"))
      assert(Int64Tensor.fromProto(resultData("version")).data === Seq(1))
    }

    it("Pipeline executor success route") {
      val e1: ServableExec[IO] = (request: ServableRequest) => IO {
        println(s"[exec1] got ${request.show}")
        val toInject = Map(
          "exec1" -> StringTensor(TensorShape.scalar, Seq("exec1")).toProto
        )
        val resp = ServableResponse(
          data = (request.data ++ toInject).asRight,
          latency = 1
        )
        println(s"[exec1] return ${resp.show}")
        resp
      }
      val e2: ServableExec[IO] = (request: ServableRequest) => IO {
        println(s"[exec2] got ${request.show}")

        val toInject = Map(
          "exec2" -> StringTensor(TensorShape.scalar, Seq("exec2")).toProto
        )
        val resp = ServableResponse(
          data = (request.data ++ toInject).asRight,
          latency = 2
        )
        println(s"[exec2] return ${resp.show}")
        resp
      }
      val e3: ServableExec[IO] = (request: ServableRequest) => IO {
        println(s"[exec3] got ${request.show}")

        val toInject = Map(
          "exec3" -> StringTensor(TensorShape.scalar, Seq("exec3")).toProto
        )
        val resp = ServableResponse(
          data = (request.data ++ toInject).asRight,
          latency = 3
        )
        println(s"[exec3] return ${resp.show}")
        resp
      }
      val execs = NonEmptyList.of[ServableExec[IO]](e1, e2, e3)

      val pipeline = ApplicationExecutor.pipelineExecutor[IO](execs)

      val requestData = Map(
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      )
      val request = ServableRequest.forNow[IO](requestData).unsafeRunSync()
      val result = pipeline.predict(request).unsafeRunSync()
      val data = result.data.right.get
      assert(StringTensor.fromProto(data("test")).data === Seq("hello"))
      assert(StringTensor.fromProto(data("exec1")).data === Seq("exec1"))
      assert(StringTensor.fromProto(data("exec2")).data === Seq("exec2"))
      assert(StringTensor.fromProto(data("exec3")).data === Seq("exec3"))
    }

    it("Application executor") {
      val servable11 = StoredServable("A", 420, 100, StoredModelVersion(1, 1, "a", ModelSignature.defaultInstance, "Ok"))
      val servable12 = StoredServable("AY", 420, 100, StoredModelVersion(2, 2, "a", ModelSignature.defaultInstance, "Ok"))
      val servable13 = StoredServable("AYA", 420, 100, StoredModelVersion(3, 3, "a", ModelSignature.defaultInstance, "Ok"))
      val storedStage1 = StoredStage("test-stage-1", NonEmptyList.of(servable11, servable12, servable13), ModelSignature.defaultInstance)

      val servable21 = StoredServable("A", 420, 100, StoredModelVersion(1, 1, "b", ModelSignature.defaultInstance, "Ok"))
      val servable22 = StoredServable("AY", 420, 100, StoredModelVersion(2, 2, "b", ModelSignature.defaultInstance, "Ok"))
      val servable23 = StoredServable("AYA", 420, 100, StoredModelVersion(3, 3, "b", ModelSignature.defaultInstance, "Ok"))
      val storedStage2 = StoredStage("test-stage-2", NonEmptyList.of(servable21, servable22, servable23), ModelSignature.defaultInstance)

      val storedApp = StoredApplication(1, "test-application", None, ModelSignature.defaultInstance, NonEmptyList.of(storedStage1, storedStage2))

      val servableCtor: Types.ServableCtor[IO] = { x =>
        IO {
          req: ServableRequest =>
            IO {
              println(s"[${x.modelVersion.name}:${x.modelVersion.version}] got: ${req.show}")
              val resp = ServableResponse(
                data = Map(
                  "model" -> StringTensor(TensorShape.scalar, Seq(x.modelVersion.name)).toProto,
                  "version" -> Int64Tensor(TensorShape.scalar, Seq(x.modelVersion.version)).toProto
                ).asRight,
                latency = 0
              )
              println(s"[${x.modelVersion.name}:${x.modelVersion.version}] return: ${resp.show}")
              resp
            }
        }
      }
      val shadowLog = mutable.ListBuffer.empty[(ServableRequest, AssociatedResponse, Option[ApplicationInfo])]
      val shadow: MonitorExec[IO] = (
        req: ServableRequest,
        resp: AssociatedResponse,
        appInfo: Option[ApplicationInfo]
      ) => {
        IO{
          shadowLog += Tuple3(req, resp, appInfo)
          ExecutionMetadata.defaultInstance
        }
      }
      val selector = new ResponseSelector[IO] {
        override def chooseOne(resps: NonEmptyList[AssociatedResponse]): IO[AssociatedResponse] = {
          IO(resps.head)
        }
      }

      val executor = ApplicationExecutor.appExecutor(storedApp, shadow, servableCtor, selector).unsafeRunSync()

      val requestData = Map(
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      )
      val request = ServableRequest.forNow[IO](requestData).unsafeRunSync()

      val result = executor.predict(request).unsafeRunSync()
      val data = result.data.right.get
      println("Application result")
      data.foreach(x => println(x.show))
      println("SHADOW log")
      shadowLog.foreach(x => println(x.show))
      assert(shadowLog.nonEmpty)
    }
  }
}