package io.hydrosphere.serving.gateway.grpc

import java.util.concurrent.Executors

import cats.data.NonEmptyList
import cats.effect._
import cats.effect.implicits._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Functor}
import io.grpc.ManagedChannelBuilder
import io.hydrosphere.serving.gateway.config.{Configuration, MonitoringConfig}
import io.hydrosphere.serving.gateway.grpc.PredictionWithMetadata.PredictionOrException
import io.hydrosphere.serving.gateway.grpc.reqstore.ReqStore
import io.hydrosphere.serving.gateway.service.application.ExecutionMeta
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring._
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

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
  
    def default[F[_] : Functor : LiftIO](cfg: MonitoringConfig, deadline: Duration): Reporter[F] = {
      val builder = ManagedChannelBuilder.forAddress(cfg.host, cfg.port)
      builder.enableRetry()
      val channel = builder.build()
      val stub = MonitoringServiceGrpc.stub(channel)
    
      Reporter.fromFuture(info =>
        stub.withDeadlineAfter(deadline.length, deadline.unit).analyze(info)
      )
    }
  }

}


trait Reporting[F[_]] {
  def report(request: PredictRequest, eu: ExecutionMeta, value: PredictionOrException): F[Unit]
}

object Reporting {

  type MKInfo[F[_]] = (PredictRequest, ExecutionMeta, PredictionOrException) => F[ExecutionInformation]

  def default[F[_]](conf: Configuration)(
    implicit F: Concurrent[F], cs: ContextShift[F]
  ): F[Reporting[F]] = {

    val appConf = conf.application
    val deadline = appConf.grpc.deadline
    
    val monitoring = Reporters.Monitoring.default(appConf.monitoring, deadline)

    val es = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutorService(es)
    prepareMkInfo(conf) map (create0(_, NonEmptyList.of(monitoring), ec))
  }

  def create0[F[_]: Concurrent](
    mkInfo: MKInfo[F],
    reporters: NonEmptyList[Reporter[F]],
    ec: ExecutionContext
  )(implicit cs: ContextShift[F]): Reporting[F] = {
    new Reporting[F] {
      def report(
        request: PredictRequest,
        eu: ExecutionMeta,
        value: PredictionOrException
      ): F[Unit] = {
        cs.evalOn(ec) {
         mkInfo(request, eu, value).flatMap(info => {
           reporters.traverse(r => r.send(info)).attempt.start
         })
        }.void
      }
    }
  }

  private def prepareMkInfo[F[_]](conf: Configuration)(implicit F: Async[F]): F[MKInfo[F]] = {
    if (conf.application.reqstore.enabled) {
      ReqStore.create[F, (PredictRequest, ResponseOrError)](conf.application.reqstore)
        .map(s => {
          (req: PredictRequest, eu: ExecutionMeta, resp: PredictionOrException) => {
            s.save(eu.serviceName, (req, responseOrError(resp)))
              .attempt
              .map(d => mkExecutionInformation(req, eu, resp, d.toOption))
          }
        })
    } else {
      val f = (req: PredictRequest, eu: ExecutionMeta, value: PredictionOrException) =>
        mkExecutionInformation(req, eu, value, None).pure
      f.pure
    }
  }


  def responseOrError(poe: PredictionOrException) = {
    poe match {
      case Left(err) => ResponseOrError.Error(ExecutionError(err.getMessage))
      case Right(v) => ResponseOrError.Response(v.response)
    }
  }

  private def mkExecutionInformation(
    request: PredictRequest,
    eu: ExecutionMeta,
    value: PredictionOrException,
    traceData: Option[TraceData]
  ): ExecutionInformation = {
    val ap: Option[ExecutionMetadata] => ExecutionInformation = ExecutionInformation.apply(Option(request), _, responseOrError(value))
    val metadata = value match {
      case Left(_) =>
        Option(ExecutionMetadata(
          applicationId = eu.applicationId,
          stageId = eu.stageId,
          modelVersionId = -1,
          signatureName = eu.signatureName,
          applicationRequestId = eu.applicationRequestId.getOrElse(""),
          requestId = eu.applicationRequestId.getOrElse(""), //todo fetch from response,
          applicationNamespace = eu.applicationNamespace.getOrElse(""),
          traceData = traceData
        ))
      case Right(v) =>
        Option(ExecutionMetadata(
          applicationId = eu.applicationId,
          stageId = eu.stageId,
          modelVersionId = v.modelVersionId.flatMap(x => Try(x.toLong).toOption).getOrElse(eu.modelVersionId),
          signatureName = eu.signatureName,
          applicationRequestId = eu.applicationRequestId.getOrElse(""),
          requestId = eu.applicationRequestId.getOrElse(""), //todo fetch from response,
          applicationNamespace = eu.applicationNamespace.getOrElse(""),
          traceData = traceData
        ))
    }
    ap(metadata)
  }

  def noop[F[_]](implicit F: Applicative[F]): Reporting[F] = (_, _, _) => F.pure(())
}