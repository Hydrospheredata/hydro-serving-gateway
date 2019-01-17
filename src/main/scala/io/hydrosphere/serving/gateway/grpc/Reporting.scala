package io.hydrosphere.serving.gateway.grpc

import cats.data.NonEmptyList
import cats.effect.{Async, IO, LiftIO}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Functor, Monad}
import io.grpc.Channel
import io.hydrosphere.serving.gateway.config.Configuration
import io.hydrosphere.serving.gateway.grpc.reqstore.{Destination, ReqStore}
import io.hydrosphere.serving.gateway.service.application.ExecutionUnit
import io.hydrosphere.serving.grpc.AuthorityReplacerInterceptor
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.MonitoringServiceGrpc.MonitoringServiceStub
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionInformation, ExecutionMetadata, MonitoringServiceGrpc, TraceData}
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest

import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait Reporter[F[_]] {
  def send(execInfo: ExecutionInformation): F[Unit]
}

object Reporter {

  def apply[F[_]](f: ExecutionInformation => F[Unit]): Reporter[F] =
    new Reporter[F] {
      def send(execInfo: ExecutionInformation): F[Unit] = f(execInfo)
    }

  def fromFuture[F[_]: Functor, A](f: ExecutionInformation => Future[A])(implicit F: LiftIO[F]): Reporter[F] =
    Reporter(info => F.liftIO(IO.fromFuture(IO(f(info)))).void)

}

object Reporters {

  object Monitoring {

    def envoyBased[F[_]: Functor: LiftIO](
      channel: Channel,
      destination: String,
      deadline: Duration
    ): Reporter[F] = {
      val stub = MonitoringServiceGrpc.stub(channel)
      monitoringGrpc(deadline, destination, stub)
    }

    def monitoringGrpc[F[_] : Functor : LiftIO](
      deadline: Duration,
      destination: String,
      grpcClient: MonitoringServiceStub
    ): Reporter[F] = {
      Reporter.fromFuture(info => {
        grpcClient
          .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, destination)
          .withDeadlineAfter(deadline.length, deadline.unit)
          .analyze(info)
      })
    }

  }

}


trait Reporting[F[_]] {
  def report(request: PredictRequest, eu: ExecutionUnit, value: ResponseOrError): F[Unit]
}

object Reporting {

  type MKInfo[F[_]] = (PredictRequest, ExecutionUnit, ResponseOrError) => F[ExecutionInformation]

  def default[F[_]](channel: Channel, conf: Configuration)(
    implicit F: Async[F]
  ): F[Reporting[F]] = {

    val appConf = conf.application
    val deadline = appConf.grpc.deadline
    val monitoring = Reporters.Monitoring.envoyBased(channel, appConf.monitoringDestination, deadline)
    val dataProfiler = Reporters.Monitoring.envoyBased(channel, appConf.profilingDestination, deadline)

    prepareMkInfo(conf) map (create0(_, NonEmptyList.of(monitoring, dataProfiler)))
  }

  // todo ContextShift + special ExecutionContext
  def create0[F[_]: Monad](
    mkInfo: (PredictRequest, ExecutionUnit, ResponseOrError) => F[ExecutionInformation],
    reporters: NonEmptyList[Reporter[F]]
  ): Reporting[F] = {
    new Reporting[F] {
      def report(
        request: PredictRequest,
        eu: ExecutionUnit,
        value: ResponseOrError
      ): F[Unit] = {
        mkInfo(request, eu, value).flatMap(info => {
          reporters.traverse(r => r.send(info)).void
        })
      }
    }
  }

  private def prepareMkInfo[F[_]](conf: Configuration)(implicit F: Async[F]): F[MKInfo[F]] = {
    if (conf.application.reqstore.enabled) {
      val destination = Destination.fromHttpServiceAddr(conf.application.reqstore.address, conf.sidecar)
      ReqStore.create[F, (PredictRequest, ResponseOrError)](destination)
        .map(s => {
          (req: PredictRequest, eu: ExecutionUnit, resp: ResponseOrError) => {
            s.save(eu.serviceName, (req, resp))
              .attempt
              .map(d => mkExecutionInformation(req, eu, resp, d.toOption))
          }
        })
    } else {
      val f = (req: PredictRequest, eu: ExecutionUnit, value: ResponseOrError) =>
        mkExecutionInformation(req, eu, value, None).pure
      f.pure
    }
  }

  private def mkExecutionInformation(
    request: PredictRequest,
    eu: ExecutionUnit,
    value: ResponseOrError,
    traceData: Option[TraceData]
  ): ExecutionInformation = {
    ExecutionInformation(
      metadata = Option(ExecutionMetadata(
        applicationId = eu.stageInfo.applicationId,
        stageId = eu.stageInfo.stageId,
        modelVersionId = eu.stageInfo.modelVersionId.getOrElse(-1),
        signatureName = eu.stageInfo.signatureName,
        applicationRequestId = eu.stageInfo.applicationRequestId.getOrElse(""),
        requestId = eu.stageInfo.applicationRequestId.getOrElse(""), //todo fetch from response,
        applicationNamespace = eu.stageInfo.applicationNamespace.getOrElse(""),
        traceData = traceData
      )),
      request = Option(request),
      responseOrError = value
    )
  }

  def noop[F[_]](implicit F: Applicative[F]): Reporting[F] = (_, _, _) => F.pure(())
}

