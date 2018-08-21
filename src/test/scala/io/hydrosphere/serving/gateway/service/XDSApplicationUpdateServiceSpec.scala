package io.hydrosphere.serving.gateway.service

import com.google.protobuf.any.Any
import envoy.api.v2.{DiscoveryRequest, DiscoveryResponse}
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryService
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.gateway.GenericTest
import io.hydrosphere.serving.manager.grpc.applications.{Application, ExecutionGraph, ExecutionStage}
import org.mockito.{Matchers, Mockito}
import org.mockito.invocation.InvocationOnMock

class XDSApplicationUpdateServiceSpec extends GenericTest {
  describe("XDSApplicationUpdateService") {
    val testApp = Application(
      id = 1,
      name = "test_app",
      contract = Some(ModelContract(modelName = "test_app")),
      executionGraph = Some(ExecutionGraph(Seq(ExecutionStage())))
    )

    it("should detect an application from stream") {
      var obs: StreamObserver[DiscoveryRequest] = null

      val xds = mock[AggregatedDiscoveryService]
      when(xds.streamAggregatedResources(Matchers.any())).thenAnswer{ invocation: InvocationOnMock =>
        val request = invocation.getArgumentAt(0, classOf[StreamObserver[DiscoveryResponse]])
        obs = new StreamObserver[DiscoveryRequest] {
          override def onNext(value: DiscoveryRequest): Unit = {
            request.onNext(DiscoveryResponse(
              versionInfo = "420",
              resources = Seq(
                Any.pack(testApp)
              )
            ))
          }

          override def onError(t: Throwable): Unit = {
            println(t)
          }

          override def onCompleted(): Unit = {
            println("completed")
          }
        }
        obs
      }

      val storeMock = mock[ApplicationStorage]
      val s = new XDSApplicationUpdateService(storeMock, xds)
      s.getUpdates()
      Mockito.verify(storeMock, Mockito.times(1)).update(Seq(testApp), "420")
      assert(true)
    }

    it("should ignore non-applications from stream") {
      var obs: StreamObserver[DiscoveryRequest] = null

      val xds = mock[AggregatedDiscoveryService]
      when(xds.streamAggregatedResources(Matchers.any())).thenAnswer{ invocation: InvocationOnMock =>
        val request = invocation.getArgumentAt(0, classOf[StreamObserver[DiscoveryResponse]])
        obs = new StreamObserver[DiscoveryRequest] {
          override def onNext(value: DiscoveryRequest): Unit = {
            request.onNext(DiscoveryResponse(
              versionInfo = "420",
              resources = Seq(
                Any.pack(ModelContract.defaultInstance)
              )
            ))
          }

          override def onError(t: Throwable): Unit = {
            println(t)
          }

          override def onCompleted(): Unit = {
            println("completed")
          }
        }
        obs
      }

      val storeMock = mock[ApplicationStorage]
      val s = new XDSApplicationUpdateService(storeMock, xds)
      s.getUpdates()
      Mockito.verify(storeMock, Mockito.never()).update(Seq(testApp), "420")
      assert(true)
    }
  }
}
