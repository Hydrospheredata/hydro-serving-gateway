package io.hydrosphere.serving.gateway.persistence.application

import cats._
import cats.data.NonEmptyList
import cats.implicits._
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.{Int64Tensor, TensorProto}
import io.hydrosphere.serving.tensorflow.types.DataType

import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.Random

final case class PredictOut(
  result: Either[Throwable, PredictResponse],
  latency: Long,
  modelVersionId: Long
)

trait PredictDownstream {
  def send(req: PredictRequest): Future[PredictOut]
  def close(): Future[Unit]
}

object PredictDownstream {
  
  type GRPCStub = PredictionServiceGrpc.PredictionServiceStub
  
  def create(servables: NonEmptyList[StoredService], deadline: Duration, sys: ActorSystem): PredictDownstream = {
    if (servables.length == 1) {
      val s = servables.head
      single(s, deadline)
    } else {
      balanced(servables, deadline, sys)
    }
  }
  
  def single(service: StoredService, deadline: Duration): PredictDownstream = {
    val stubAndChannel = mkStubAndChannel(service, deadline)
    
    new PredictDownstream {
      override def send(req: PredictRequest): Future[PredictOut] =
        stubAndChannel.call(req)
  
      override def close(): Future[Unit] = stubAndChannel.close()
    }
  }
  
  
  def balanced(servables: NonEmptyList[StoredService], deadline: Duration, sys: ActorSystem): PredictDownstream = {
    
    val weightedServices = servables.map(s => s.weight -> mkStubAndChannel(s, deadline)).toList.toMap
    
    val distributor = Distributor.weighted(weightedServices)
    
    val balancer = sys.actorOf(BalancedDownstream.props(distributor))
    new PredictDownstream {
      
      override def send(req: PredictRequest): Future[PredictOut] = {
        val ps = Promise[PredictOut]
        balancer ! BalancedDownstream.Send(req, ps)
        ps.future
      }
      
      override def close(): Future[Unit] = {
        balancer ! PoisonPill
        val closes = weightedServices.values.map(_.close())
        Future.sequence(closes).map(_ => ())
      }
    }
  }
  
  class StubAndChannel(
    stub: GRPCStub,
    deadline: Duration,
    channel: ManagedChannel,
    modelVersionId: Long){
    
    def call(req: PredictRequest): Future[PredictOut] = {
      for {
        start  <- Future.successful(TimeUnit.MILLISECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS))
        result <- stub.withDeadlineAfter(deadline.length, deadline.unit).predict(req).attempt
      } yield  {
        val end = TimeUnit.MILLISECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS)
        val latency = end - start
        val withInternalInfo = result.map(resp => {
          val info = List(
            "system.latency" -> Int64Tensor(TensorShape.scalar, Seq(latency)).toProto,
            "modelVersionId" -> Int64Tensor(TensorShape.scalar, Seq(modelVersionId)).toProto
          )
          resp.addAllInternalInfo(info)
        })
        PredictOut(withInternalInfo, latency, modelVersionId)
      }
    }
    
    def close(): Future[Unit] = Future {
      channel.shutdown()
      channel.awaitTermination(1, TimeUnit.SECONDS)
      ()
    }
    
  }
  
  def mkStubAndChannel(service: StoredService, deadline: Duration): StubAndChannel = {
    mkStubAndChannel(service.host, service.port, service.modelVersion.id, deadline)
  }
  
  def mkStubAndChannel(host: String, port: Int, modelVersionId: Long, deadline: Duration): StubAndChannel = {
    val builder = ManagedChannelBuilder
      .forAddress(host, port)
    
    builder.usePlaintext()
    builder.enableRetry()
    
    val channel = builder.build()
    val stub = PredictionServiceGrpc.stub(channel)
    new StubAndChannel(stub, deadline, channel, modelVersionId)
  }
  
  
  trait Distributor[A] {
    def next: A
    def all: List[A]
  }
  object Distributor {
    
    def weighted[A](values: Map[Int, A]): Distributor[A] = {
      val (total, upd) = values.foldLeft((0, Map.empty[Int, A]))({
        case ((acc, map), (weight, v)) =>
          val nextAcc = acc + weight
          val nextMap = map + (nextAcc -> v)
          nextAcc -> nextMap
      })
      val treeMap = TreeMap(upd.toList: _*)
      val rdn = new Random()
      
      new Distributor[A] {
        override def next: A = {
          val v = rdn.nextInt(total)
          treeMap.from(v).headOption match {
            case Some((_, a)) => a
            case None => treeMap.head._2
          }
        }
  
        override def all: List[A] =
          treeMap.values.toList
      }
    }
  }
  
  class BalancedDownstream(
    distributor: Distributor[StubAndChannel]
  ) extends Actor {
    
    override def receive: Receive = {
      case BalancedDownstream.Send(req, p) =>
        val f = distributor.next.call(req)
        p.completeWith(f)
      
    }
  }
  
  object BalancedDownstream {
    case class Send(req: PredictRequest, p: Promise[PredictOut])
    def props(distributor: Distributor[StubAndChannel]): Props = Props(classOf[BalancedDownstream], distributor)
  }
 
 
}


