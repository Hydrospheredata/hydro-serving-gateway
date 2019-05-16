package io.hydrosphere.serving.gateway.util

import java.util.concurrent.locks.{Lock, ReentrantReadWriteLock}

import cats.effect.{Resource, Sync}

trait ReadWriteLock[F[_]] {
  def read: Resource[F, Unit]

  def write: Resource[F, Unit]
}

object ReadWriteLock {
  def reentrant[F[_]](implicit F: Sync[F]): F[ReadWriteLock[F]] = F.delay {
    new ReadWriteLock[F] {
      private[this] val rwLock = new ReentrantReadWriteLock()

      def getLock[A](l: Lock): Resource[F, Unit] = {
        Resource.make(F.delay(l.lock()))(_ => F.delay(l.unlock()))
      }

      override def read = {
        getLock(rwLock.readLock())
      }

      override def write  = {
        getLock(rwLock.writeLock())
      }
    }
  }
}