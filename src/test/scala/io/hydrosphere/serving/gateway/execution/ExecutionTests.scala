package io.hydrosphere.serving.gateway.execution

import cats.data.NonEmptyList
import cats.effect.{Clock, IO}
import cats.implicits._
import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.Contract.ValidationErrors
import io.hydrosphere.serving.gateway.{Contract, GenericTest}
import io.hydrosphere.serving.gateway.execution.application._
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.execution.servable.{Predictor, ServableRequest, ServableResponse}
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationInMemoryStorage, ApplicationStorage}
import io.hydrosphere.serving.gateway.persistence.servable.ServableInMemoryStorage
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredModelVersion, StoredServable, StoredStage}
import io.hydrosphere.serving.gateway.util.ShowInstances._
import io.hydrosphere.serving.gateway.util.{RandomNumberGenerator, ReadWriteLock, UUIDGenerator}
import io.hydrosphere.serving.model.api.ValidationError
import io.hydrosphere.serving.monitoring.metadata.{ApplicationInfo, ExecutionMetadata, TraceData}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.{Int64Tensor, StringTensor}
import io.hydrosphere.serving.tensorflow.types.DataType

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

class ExecutionTests extends GenericTest {
  describe("Executors") {
    implicit val clock = Clock.create[IO]
    implicit val cs = IO.contextShift(ExecutionContext.global)
    implicit val uuid = UUIDGenerator[IO]
    implicit val rng = RandomNumberGenerator.default[IO].unsafeRunSync()

    val contract = ModelSignature(
      signatureName = "predict",
      inputs = Seq(ModelField(
        name = "test",
        shape = None,
        typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_STRING)
      )),
      outputs = Seq(
        ModelField(
          name = "test",
          shape = None,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_STRING)
        ),
        ModelField(
          name = "dummy",
          shape = None,
          typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_STRING)
        )
      )
    )

    it("single servable without shadow") {
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
      var shadowEmpty = true
      val shadow = new MonitoringClient[IO] {
        override def monitor(request: ServableRequest, response: AssociatedResponse, appInfo: Option[ApplicationInfo]): IO[ExecutionMetadata] = {
          shadowEmpty = false
          IO(ExecutionMetadata.defaultInstance)
        }
      }
      val lock = ReadWriteLock.reentrant[IO].unsafeRunSync()
      val servableStorage = new ServableInMemoryStorage[IO](lock, clientCtor, shadow)
      servableStorage.add(Seq(StoredServable("test", "AYAYA", 420, 100, StoredModelVersion(42, 1, "test", contract, "Ok")))).unsafeRunSync()

      val appStorage = new ApplicationStorage[IO] {
        override def getByName(name: String): IO[Option[StoredApplication]] = ???
        override def getById(id: Long): IO[Option[StoredApplication]] = ???
        override def getExecutor(name: String): IO[Option[Predictor[IO]]] = ???
        override def listAll: IO[List[StoredApplication]] = ???
        override def addApps(apps: List[StoredApplication]): IO[Unit] = ???
        override def removeApps(ids: Seq[String]): IO[List[StoredApplication]] = ???
      }

      val executionService = ExecutionService.makeDefault[IO](appStorage, servableStorage).unsafeRunSync()

      val request = PredictRequest(
        modelSpec = ModelSpec("test").some,
        inputs = Map(
          "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
        )
      )
      val response = executionService.predictWithoutShadow(request).unsafeRunSync()
      val data = response.outputs
      assert(data.contains("dummy"))
      assert(shadowEmpty)
    }

    it("shoud fail execution if output violates the contract") {
      val clientCtor = new PredictionClient.Factory[IO] {
        override def make(host: String, port: Int): IO[PredictionClient[IO]] = IO {
          new PredictionClient[IO] {
            override def predict(request: PredictRequest): IO[PredictResponse] = IO {
              PredictResponse(request.inputs)
            }

            override def close(): IO[Unit] = IO.unit
          }
        }
      }
      val mv = StoredModelVersion(42, 1, "test", contract, "Ok")
      val servable = StoredServable("s1", "AYAYA", 420, 100, mv)
      val exec = Predictor.forServable(servable, clientCtor).unsafeRunSync()
      val shadowState = ListBuffer.empty[ExecutionMetadata]
      val shadow: MonitoringClient[IO] = (req, resp, appInfo) => {
        val entry = MonitoringClient.mkExecutionMetadata(mv, req.replayTrace, None, appInfo, resp.resp.latency, req.requestId)
        shadowState += entry
        IO(entry)
      }
      val shadowed = Predictor.withShadow(servable, exec, shadow, None)
      val requestData = Map(
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      )
      val request = ServableRequest(requestData, "", TraceData(42, 1).some)
      val response = shadowed.predict(request).unsafeRunSync()
      assert(response.data.isLeft)
      val errors = response.data.left.get.asInstanceOf[ValidationErrors].errors
      assert(errors.head == Contract.MissingField("dummy"))
    }

    it("Servable with shadowing execution") {
      val shadowState = ListBuffer.empty[(ServableRequest, AssociatedResponse, Option[ApplicationInfo])]
      val shadow: MonitoringClient[IO] = (req, resp, appInfo) => {
        val entry = (req, resp, appInfo)
        shadowState += entry
        IO(ExecutionMetadata.defaultInstance)
      }
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

      val servable = StoredServable("shadow-me", "AYAYA", 420, 100, StoredModelVersion(42, 2, "shadowed", contract, "Ok"))

      val lock = ReadWriteLock.reentrant[IO].unsafeRunSync()
      val servableStorage = new ServableInMemoryStorage[IO](lock, clientCtor, shadow)
      servableStorage.add(Seq(servable)).unsafeRunSync()

      val selector = ResponseSelector.randomSelector[IO]

      val appStorage = new ApplicationInMemoryStorage[IO](lock, servableStorage.getExecutor, shadow, selector)

      val executionService = ExecutionService.makeDefault[IO](appStorage, servableStorage).unsafeRunSync()

      val request = PredictRequest(
        modelSpec = ModelSpec("shadow-me").some,
        inputs = Map(
          "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
        ))
      val response = executionService.predict(request).unsafeRunSync()

      val data = response.outputs
      assert(data.contains("dummy"))
      assert(shadowState.length == 1)
      assert(shadowState.head._1.data === request.inputs)
      assert(shadowState.head._2.servable === servable)
      assert(shadowState.head._2.resp.data == Map(
        "dummy" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto,
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      ).asRight)
      assert(shadowState.head._3 === None)
    }

    it("replay request for servable") {
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
      val mv = StoredModelVersion(42, 1, "test", contract, "Ok")
      val servable = StoredServable("s1", "AYAYA", 420, 100, mv)
      val exec = Predictor.forServable(servable, clientCtor).unsafeRunSync()
      val shadowState = ListBuffer.empty[ExecutionMetadata]
      val shadow: MonitoringClient[IO] = (req, resp, appInfo) => {
        val entry = MonitoringClient.mkExecutionMetadata(mv, req.replayTrace, None, appInfo, resp.resp.latency, req.requestId)
        shadowState += entry
        IO(entry)
      }
      val shadowed = Predictor.withShadow(servable, exec, shadow, None)
      val requestData = Map(
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      )
      val request = ServableRequest(requestData, "", TraceData(42, 1).some)
      val response = shadowed.predict(request).unsafeRunSync()
      assert(response.data.isRight, response.data)
      val data = response.data.right.get
      assert(data.contains("dummy"))
      assert(shadowState.length == 1)
      assert(shadowState.head.originTraceData === TraceData(42, 1).some)
      assert(shadowState.head.modelName == mv.name)
    }

    it("Stage execution") {
      val servable1 = StoredServable("s1", "A", 420, 100, StoredModelVersion(1, 1, "test", ModelSignature.defaultInstance, "Ok"))
      val servable2 = StoredServable("s2", "AY", 420, 100, StoredModelVersion(2, 2, "test", ModelSignature.defaultInstance, "Ok"))
      val servable3 = StoredServable("s3", "AYA", 420, 100, StoredModelVersion(3, 3, "test", ModelSignature.defaultInstance, "Ok"))
      val storedStage = StoredStage("test-stage", NonEmptyList.of(servable1, servable2, servable3), ModelSignature.defaultInstance)
      val storedApp = StoredApplication(1, "test-application", None, ModelSignature.defaultInstance, NonEmptyList.of(storedStage))

      val servableCtor: Types.PredictorCtor[IO] = { x =>
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
      val shadow: MonitoringClient[IO] = (_: ServableRequest, _: AssociatedResponse, _: Option[ApplicationInfo]) => {
        IO(ExecutionMetadata.defaultInstance)
      }
      val selector = new ResponseSelector[IO] {
        override def chooseOne(resps: NonEmptyList[AssociatedResponse]): IO[AssociatedResponse] = {
          IO(resps.head)
        }
      }

      val stageExec = StagePredictor.withShadow(storedApp, storedStage, servableCtor, shadow, selector).unsafeRunSync()

      val requestData = Map(
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      )
      val request = ServableRequest(requestData, "")
      val result = stageExec.predict(request).unsafeRunSync()
      assert(result.data.isRight, result.data)
      val resultData = result.data.right.get
      println(resultData)
      assert(StringTensor.fromProto(resultData("model")).data === Seq("test"))
      assert(Int64Tensor.fromProto(resultData("version")).data === Seq(1))
    }

    it("Pipeline executor success route") {
      val e1: Predictor[IO] = (request: ServableRequest) => IO {
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
      val e2: Predictor[IO] = (request: ServableRequest) => IO {
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
      val e3: Predictor[IO] = (request: ServableRequest) => IO {
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
      val execs = NonEmptyList.of[Predictor[IO]](e1, e2, e3)

      val pipeline = ApplicationExecutor.pipelineExecutor[IO](execs)

      val requestData = Map(
        "test" -> StringTensor(TensorShape.scalar, Seq("hello")).toProto
      )
      val request = ServableRequest(requestData, "")
      val result = pipeline.predict(request).unsafeRunSync()
      val data = result.data.right.get
      assert(StringTensor.fromProto(data("test")).data === Seq("hello"))
      assert(StringTensor.fromProto(data("exec1")).data === Seq("exec1"))
      assert(StringTensor.fromProto(data("exec2")).data === Seq("exec2"))
      assert(StringTensor.fromProto(data("exec3")).data === Seq("exec3"))
    }

    it("Application executor") {
      val servable11 = StoredServable("s1", "A", 420, 100, StoredModelVersion(1, 1, "a", ModelSignature.defaultInstance, "Ok"))
      val servable12 = StoredServable("s2", "AY", 420, 100, StoredModelVersion(2, 2, "a", ModelSignature.defaultInstance, "Ok"))
      val servable13 = StoredServable("s3", "AYA", 420, 100, StoredModelVersion(3, 3, "a", ModelSignature.defaultInstance, "Ok"))
      val storedStage1 = StoredStage("test-stage-1", NonEmptyList.of(servable11, servable12, servable13), ModelSignature.defaultInstance)

      val servable21 = StoredServable("s4", "A", 420, 100, StoredModelVersion(1, 1, "b", ModelSignature.defaultInstance, "Ok"))
      val servable22 = StoredServable("s5", "AY", 420, 100, StoredModelVersion(2, 2, "b", ModelSignature.defaultInstance, "Ok"))
      val servable23 = StoredServable("s6", "AYA", 420, 100, StoredModelVersion(3, 3, "b", ModelSignature.defaultInstance, "Ok"))
      val storedStage2 = StoredStage("test-stage-2", NonEmptyList.of(servable21, servable22, servable23), ModelSignature.defaultInstance)

      val storedApp = StoredApplication(1, "test-application", None, ModelSignature.defaultInstance, NonEmptyList.of(storedStage1, storedStage2))

      val servableCtor: Types.PredictorCtor[IO] = { x =>
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
      val shadow: MonitoringClient[IO] = (
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
      val request = ServableRequest(requestData, "")

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