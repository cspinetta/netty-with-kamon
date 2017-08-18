package base

import java.util.function.Consumer

import io.netty.channel.{Channel, ChannelFuture}
import io.netty.util.concurrent.{ImmediateEventExecutor, Promise, Future => NFuture}


// from: https://github.com/novarto-oss/netty-future-monad
object NettySugar {

  object FutureUtil {
    def failOrEffect[A, B](upstream: NFuture[A], promise: Promise[B], f: () => Unit): Unit = {
      upstream.addListener((x: NFuture[A]) => {
        if (upstream.isCancelled) promise.cancel(false)
        else if (upstream.cause != null) promise.setFailure(x.cause)
        else f()
      })
    }

    def addCallback[T](future: NFuture[T], onSuccess: Consumer[T], onFailure: Consumer[Throwable]): Unit = {
      future.addListener((x: NFuture[T]) => {
        val cause = x.cause
        if (x.isCancelled && cause == null) throw new IllegalStateException
        if (cause != null) onFailure.accept(cause)
        else onSuccess.accept(future.getNow)
      })
    }
  }

  object FutureMonad {
    import FutureUtil._

    def map[A, B](fut: NFuture[A], f: A => B): NFuture[B] = {
      val result: Promise[B] = ImmediateEventExecutor.INSTANCE.newPromise.asInstanceOf[Promise[B]]
      failOrEffect(fut.asInstanceOf[NFuture[A]], result, () => result.setSuccess(f(fut.getNow)))
      result
    }

    def flatMap[A, B](fut: NFuture[A], f: A => NFuture[B]): NFuture[B] = {
      val promise = ImmediateEventExecutor.INSTANCE.newPromise.asInstanceOf[Promise[B]]
      failOrEffect(fut, promise, () => {
        val fut2 = f(fut.getNow)
        failOrEffect(fut2, promise, () => promise.setSuccess(fut2.getNow))
      })
      promise
    }

    def unit[A](a: () => A): NFuture[A] = ImmediateEventExecutor.INSTANCE.newSucceededFuture(a())
  }

  object syntax {
    implicit class PimpedNettyFuture[A](val fut: NFuture[A]) extends AnyVal {
      def map[B](f: A => B): NFuture[B] = FutureMonad.map(fut, f)
      def flatMap[B](f: A => NFuture[B]): NFuture[B] = FutureMonad.flatMap(fut, f)
    }
    implicit class PimpedChannelFuture[A](val fut: ChannelFuture) extends AnyVal {
      def map[B](f: Channel => B): NFuture[B] = FutureMonad.map(fut.asInstanceOf[NFuture[Unit]], (_: Unit) => f(fut.channel()))
      def flatMap[B](f: Channel => NFuture[B]): NFuture[B] = FutureMonad.flatMap(fut.asInstanceOf[NFuture[Unit]], (_: Unit) => f(fut.channel()))
    }
  }
}
