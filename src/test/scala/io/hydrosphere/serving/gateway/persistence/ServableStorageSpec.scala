package io.hydrosphere.serving.gateway.persistence

import cats.effect.IO
import io.hydrosphere.serving.proto.contract.signature.ModelSignature
import io.hydrosphere.serving.gateway.GenericTest
import io.hydrosphere.serving.gateway.execution.application.{AssociatedResponse, MonitoringClient}
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.execution.servable.ServableRequest
import io.hydrosphere.serving.gateway.persistence.servable.ServableInMemoryStorage
import io.hydrosphere.serving.gateway.util.ReadWriteLock
import io.hydrosphere.monitoring.proto.sonar.entities.{ApplicationInfo, ExecutionMetadata}
import io.hydrosphere.serving.proto.runtime.api.{PredictRequest, PredictResponse}

import scala.collection.mutable
import scala.concurrent.ExecutionContext

class ServableStorageSpec extends GenericTest {
  implicit val timer = IO.timer(ExecutionContext.global)

  describe("ServableStorage") {
    it("should update existing executors on servable update") {
      val lock = ReadWriteLock.reentrant[IO].unsafeRunSync()
      val clients = mutable.HashSet.empty[(String, Int)]
      val closedClients = mutable.HashSet.empty[(String, Int)]
      val clientCtor = new PredictionClient.Factory[IO] {
        override def make(host: String, port: Int): IO[PredictionClient[IO]] = IO {
          clients += host -> port
          new PredictionClient[IO] {
            override def predict(request: PredictRequest): IO[PredictResponse] = IO.raiseError(???)

            override def close(): IO[Unit] = IO{
              clients -= host -> port
              closedClients += host -> port
            }
          }
        }
      }
      val shadow = new MonitoringClient[IO] {
        override def monitor(request: ServableRequest, response: AssociatedResponse, appInfo: Option[ApplicationInfo]): IO[ExecutionMetadata] = ???
      }
      val storage = new ServableInMemoryStorage[IO](lock, clientCtor, shadow)
      val initial = Seq(
        StoredServable("servable-1", "old-host-1", 9090, 100, StoredModelVersion(1, 1, "model-1", ModelSignature.defaultInstance, "Ok")),
        StoredServable("servable-2", "old-host-2", 9090, 100, StoredModelVersion(1, 1, "model-2", ModelSignature.defaultInstance, "Ok"))
      )
      storage.add(initial).unsafeRunSync()
      val updated = Seq(
        StoredServable("servable-1", "new-host-1", 9091, 100, StoredModelVersion(1, 1, "model-1", ModelSignature.defaultInstance, "Ok"))
      )
      storage.add(updated).unsafeRunSync()
      println(storage.list.unsafeRunSync())
      val servable = storage.get("servable-1").unsafeRunSync().get
      assert(servable.host == "new-host-1")
      assert(servable.port == 9091)
      assert(clients.size == 2)
      assert(closedClients.size == 1)
    }

    it("should handle deletion of non-existent servables") {
      val lock = ReadWriteLock.reentrant[IO].unsafeRunSync()
      val clients = mutable.HashSet.empty[(String, Int)]
      val closedClients = mutable.HashSet.empty[(String, Int)]
      val clientCtor = new PredictionClient.Factory[IO] {
        override def make(host: String, port: Int): IO[PredictionClient[IO]] = IO {
          clients += host -> port
          new PredictionClient[IO] {
            override def predict(request: PredictRequest): IO[PredictResponse] = IO.raiseError(???)

            override def close(): IO[Unit] = IO{
              clients -= host -> port
              closedClients += host -> port
            }
          }
        }
      }
      val shadow = new MonitoringClient[IO] {
        override def monitor(request: ServableRequest, response: AssociatedResponse, appInfo: Option[ApplicationInfo]): IO[ExecutionMetadata] = ???
      }
      val storage = new ServableInMemoryStorage[IO](lock, clientCtor, shadow)
      storage.remove(Seq("kek")).unsafeRunSync()
      assert(true) // shouldn't crash
    }
  }
}
