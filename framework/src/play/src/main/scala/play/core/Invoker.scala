package play.core

import play.core.server._
import play.api.libs.iteratee._

import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.http.HeaderNames._

import akka.actor.Actor
import akka.actor.Props
import akka.actor.Actor._
import akka.dispatch.Dispatchers._
import play.api.libs.akka.Akka._
import akka.dispatch.Future
import akka.dispatch.Await
import akka.util.duration._  
import akka.actor.OneForOneStrategy
import akka.routing.{DefaultActorPool,FixedCapacityStrategy,SmallestMailboxSelector}


case class HandleAction[A](request: Request[A], response: Response, action: Action[A], app: Application)
class Invoker extends Actor {

  def receive = {

    case (requestHeader: RequestHeader, bodyFunction: BodyParser[_]) => sender ! bodyFunction(requestHeader)

    case HandleAction(request, response: Response, action, app: Application) =>

      val result = try {
        // Be sure to use the Play classloader in this Thread
        Thread.currentThread.setContextClassLoader(app.classloader)
        try {
          action(request)
        } catch {
          case e: PlayException => throw e
          case e: Throwable => {

            val source = app.sources.flatMap(_.sourceFor(e))

            throw new PlayException(
              "Execution exception",
              "[%s: %s]".format(e.getClass.getSimpleName, e.getMessage),
              Some(e)) with PlayException.ExceptionSource {
              def line = source.map(_._2)
              def position = None
              def input = source.map(_._1).map(scalax.file.Path(_))
              def sourceName = source.map(_._1.getAbsolutePath)
            }

          }

        }
      } catch {
        case e => try {

          Logger.error(
            """
            |
            |! %sInternal server error, for request [%s] ->
            |""".stripMargin.format(e match {
              case p: PlayException => "@" + p.id + " - "
              case _ => ""
            }, request),
            e)

          app.global.onError(request, e)
        } catch {
          case e => DefaultGlobal.onError(request, e)
        }
      }

      response.handle {

        // Handle Flash Scope (probably not the good place to do it)
        result match {
          case r @ SimpleResult(header, _) => {

            val flashCookie = {
              header.headers.get(SET_COOKIE)
                .map(Cookies.decode(_))
                .flatMap(_.find(_.name == Flash.COOKIE_NAME)).orElse {
                  Option(request.flash).filterNot(_.isEmpty).map { _ =>
                    Cookie(Flash.COOKIE_NAME, "", 0)
                  }
                }
            }

            flashCookie.map { newCookie =>
              r.copy(header = header.copy(headers = header.headers + (SET_COOKIE -> Cookies.merge(header.headers.get(SET_COOKIE).getOrElse(""), Seq(newCookie)))))
            }.getOrElse(r)

          }
          case r => r
        }

      }

  }
}

case class Invoke[A](a: A, k: A => Unit)
class PromiseInvoker extends Actor {

  def receive = {
    case Invoke(a, k) => k(a)
  }
}
object PromiseInvoker {

  private val faultHandler = OneForOneStrategy(List(classOf[Exception]), 5, 1000)

  private lazy val pool = system.actorOf(
        Props(new Actor with DefaultActorPool with FixedCapacityStrategy with SmallestMailboxSelector {
          def instance(defaults: Props) = system.actorOf(defaults.withCreator(new PromiseInvoker).withDispatcher("invoker.promise-dispatcher"))
          def limit = 2
          def selectionCount =  1
          def partialFill = true
          def receive = _route
        }).withFaultHandler(faultHandler))
  
  val invoker = pool 
}

object Agent {

  implicit def dispatcher = system.dispatchers.lookup("invoker.socket-dispatcher")

  def apply[A](a: A) = {
    new {
      def send(action: (A => A)) { 
         Future{action(a)} 
      }
    }
  }
}


