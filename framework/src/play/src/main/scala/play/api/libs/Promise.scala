package play.api.libs.concurrent

import play.core._
import java.util.concurrent.TimeUnit

object `package` {
  type RedeemablePromise[A] = Promise[A] with Redeemable[A]
}

sealed trait PromiseValue[+A] {
  def isDefined = this match { case Waiting => false; case _ => true }
}

trait NotWaiting[+A] extends PromiseValue[A] {
  /**
   * Return the value or the promise, throw it if it held an exception
   */
  def get: A = this match {
    case Thrown(e) => throw e
    case Redeemed(a) => a
  }

  def fold[B](onError: Throwable => B, onSuccess: A => B): B = this match {
    case Thrown(e) => onError(e)
    case Redeemed(r) => onSuccess(r)
  }

}
case class Thrown(e: scala.Throwable) extends NotWaiting[Nothing]
case class Redeemed[+A](a: A) extends NotWaiting[A]
case object Waiting extends PromiseValue[Nothing]

trait Promise[+A] {

  def onRedeem(k: A => Unit): Unit

  def extend[B](k: Function1[Promise[A], B]): Promise[B]

  def extend1[B](k: Function1[NotWaiting[A], B]): Promise[B] = extend[B](p => k(p.value))

  def value = await

  def await: NotWaiting[A] = await(5000)

  def await(timeout: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): NotWaiting[A]

  def filter(p: A => Boolean): Promise[A]

  def map[B](f: A => B): Promise[B]

  def flatMap[B](f: A => Promise[B]): Promise[B]

  def or[B](other: Promise[B]): Promise[Either[A, B]] = {
    import scala.concurrent.stm._

    val p = Promise[Either[A, B]]()
    val ref = Ref(false)
    this.onRedeem { v =>
      if (!ref.single()) {
        val iRedeemed = atomic { implicit txn =>
          val before = ref()
          ref() = true
          !before
        }
        if (iRedeemed) {
          p.redeem(Left(v))
        }
      }
    }
    other.onRedeem { v =>
      if (!ref.single()) {
        val iRedeemed = atomic { implicit txn =>
          val before = ref()
          ref() = true
          !before
        }
        if (iRedeemed) {
          p.redeem(Right(v))
        }
      }
    }
    p
  }

  def orTimeout[B](message: B, duration: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): Promise[Either[A, B]] = {
    or(Promise.timeout(message, duration, unit))
  }

}

trait Redeemable[A] {
  def redeem(a: => A): Unit
  def throwing(t: Throwable): Unit
}

class STMPromise[A] extends Promise[A] with Redeemable[A] {
  import scala.concurrent.stm._

  val actions: Ref[List[Promise[A] => Unit]] = Ref(List())
  var redeemed: Ref[PromiseValue[A]] = Ref(Waiting)

  def extend[B](k: Function1[Promise[A], B]): Promise[B] = {
    val result = new STMPromise[B]()
    addAction(p => result.redeem(k(p)))
    result
  }

  def filter(p: A => Boolean): Promise[A] = {
    val result = new STMPromise[A]()
    onRedeem(a => if (p(a)) result.redeem(a))
    result
  }

  def collect[B](p: PartialFunction[A, B]) = {
    val result = new STMPromise[B]()
    onRedeem(a => p.lift(a).foreach(result.redeem(_)))
    result
  }

  def onRedeem(k: A => Unit): Unit = {
    addAction(p => p.value match { case Redeemed(a) => k(a); case _ => })
  }

  private def addAction(k: Promise[A] => Unit): Unit = {
    if (redeemed.single().isDefined) {
      k(this)
    } else {
      val ok: Boolean = atomic { implicit txn =>
        if (!redeemed().isDefined) { actions() = actions() :+ k; true }
        else false
      }
      if (!ok) invoke(this, k)
    }
  }

  def await(timeout: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): NotWaiting[A] = {
    atomic { implicit txn =>
      if (redeemed() != Waiting) redeemed().asInstanceOf[NotWaiting[A]]
      else {
        retryFor(unit.toNanos(timeout), scala.actors.threadpool.TimeUnit.NANOSECONDS)
        throw new java.util.concurrent.TimeoutException("Promise timed out after " + timeout + " : " + unit)
      }
    }
  }

  private def invoke[T](a: T, k: T => Unit) = PromiseInvoker.invoker ! Invoke(a, k)

  def redeem(body: => A): Unit = {
    val result = scala.util.control.Exception.allCatch[A].either(body)
    atomic { implicit txn =>
      if (redeemed().isDefined) sys.error("already redeemed")
      redeemed() = result.fold(Thrown(_), Redeemed(_))
    }
    actions.single.swap(List()).foreach(invoke(this, _))
  }

  def throwing(t: Throwable): Unit = {
    atomic { implicit txn =>
      if (redeemed().isDefined) sys.error("already redeemed")
      redeemed() = Thrown(t)
    }
  }

  def map[B](f: A => B): Promise[B] = {
    val result = new STMPromise[B]()
    this.addAction(p => p.value match {
      case Redeemed(a) => result.redeem(f(a))
      case Thrown(e) => result.redeem(throw e)
    })
    result
  }

  def flatMap[B](f: A => Promise[B]) = {
    val result = new STMPromise[B]()
    this.addAction(p => p.value match {
      case Redeemed(a) =>
        f(a).extend(ip => ip.value match {
          case Redeemed(a) => result.redeem(a)
          case Thrown(e) => result.redeem(throw e)

        })
      case Thrown(e) => result.redeem(throw e)
    })
    result
  }
}

object PurePromise {

  def apply[A](a: A): Promise[A] = new Promise[A] {

    private def neverRedeemed[A]: Promise[A] = new Promise[A] {
      def onRedeem(k: A => Unit): Unit = ()

      def extend[B](k: Function1[Promise[A], B]): Promise[B] = neverRedeemed[B]

      def await(timeout: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): NotWaiting[A] = throw new java.util.concurrent.TimeoutException("will never get redeemed")

      def filter(p: A => Boolean): Promise[A] = this

      def map[B](f: A => B): Promise[B] = neverRedeemed[B]

      def flatMap[B](f: A => Promise[B]): Promise[B] = neverRedeemed[B]

    }

    def onRedeem(k: A => Unit): Unit = k(a)

    def await(timeout: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): NotWaiting[A] = Redeemed(a)

    def redeem(a: A) = sys.error("Already redeemed")

    def throwing(t: Throwable) = sys.error("Already redeemed")

    def extend[B](f: (Promise[A] => B)): Promise[B] = {
      apply(f(this))
    }

    def filter(p: A => Boolean) = if (p(a)) this else neverRedeemed[A]

    def map[B](f: A => B): Promise[B] = PurePromise[B](f(a))

    def flatMap[B](f: A => Promise[B]): Promise[B] = f(a)
  }
}

object Promise {

  def pure[A](a: A) = PurePromise(a)

  def apply[A](): Promise[A] with Redeemable[A] = new STMPromise[A]()

  def timeout[A](message: A, duration: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): Promise[A] = {
    import java.util.{ Timer, TimerTask }
    val p = Promise[A]()
    new Timer("Promise.Timeout", true).schedule(new TimerTask {
      def run {
        p.redeem(message)
      }
    }, unit.toMillis(duration))
    p
  }

}

