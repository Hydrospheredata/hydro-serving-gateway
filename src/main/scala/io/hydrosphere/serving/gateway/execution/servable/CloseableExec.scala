package io.hydrosphere.serving.gateway.execution.servable

trait CloseableExec[F[_]] extends ServableExec[F] {
  def close: F[Unit]
}
