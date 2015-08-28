package play.core.server.http4s

import play.api.libs.iteratee._

import scala.concurrent._
import scala.util.{Failure, Success}
import scalaz.concurrent.Task
import scalaz.stream.Cause.EarlyCause
import scalaz.stream.Process.HaltEmitOrAwait
import scalaz.stream._
import scalaz.{-\/, NaturalTransformation, \/-}

/**
 * Created by Ugo Bataillard on 8/25/15.
 */
object ScalazConversions {

  def process[O](enum: Enumerator[O])(implicit ctx: ExecutionContext): Task[Process[Task, O]] = {
    // TODO: Check if this should be synchronous
    Task {
      val q = async.unboundedQueue[O]
      (enum |>>> Iteratee.foreach (q.enqueueOne(_).run))
        .onComplete { _ => q.close.run }
      q.dequeue
    }
  }

  // From https://github.com/mandubian/playzstream/blob/master/app/models/stream.scala

  /** Creates an enumerator from a Process[Task, O] */
  def enumerator[O](p: Process[Task, O])(implicit ctx: ExecutionContext) = new Enumerator[O] {

    val Trampoline = scalaz.Trampoline

    def apply[A](it: Iteratee[O, A]): Future[Iteratee[O, A]] = {

      def step[A](curP: Process[Task, O], curIt: Iteratee[O, A]): Future[Iteratee[O, A]] = {
        curIt.fold {
          case Step.Done(a, e) =>
            Future.successful(Done(a, e))

          case Step.Cont(k) =>
            curP match {

              case Process.Await(req, recv) =>
                scalazTask2scalaFuture(req.attempt).flatMap { r =>
                  step(recv(EarlyCause.fromTaskResult(r)).run, curIt)
                }
              // TODO: figure out if there is anything left to do here
              //                .recoverWith{
              //                  case Process.End => step(fb, curIt) // Normal termination
              //                  case e: Exception => err match {
              //                    case Process.Halt(_) => throw e // ensure exception is eventually thrown;
              //                    // without this we'd infinite loop
              //                    case _ => step(err ++ Process.eval(Task.delay(throw e)), curIt)
              //                  }
              //                }

              case Process.Emit(h) =>
                enumerateSeq(h, Cont(k)).flatMap { i => step(Process.Halt(Cause.End), i) }

              case Process.Append(h, stack) =>
                step(h, curIt) flatMap { i =>
                  if (stack.isEmpty) step(Process.Halt(Cause.End), i)
                  else {
                    step(Process
                      .Append(stack.head(Cause.End).run.asInstanceOf[HaltEmitOrAwait[Task, O]], stack.tail), i)
                  }
                }
              case Process.Halt(_) =>
                Future.successful(k(Input.EOF))
            }

          case Step.Error(msg, e) =>
            Future.successful(Error(msg, e))
        }
      }

      step(p, it)
    }
  }


  /* Task <- Future conversion */
  def scalazTask2scalaFuture[T](task: => Task[T]): Future[T] = {
    val p: Promise[T] = Promise()

    task.runAsync {
      case -\/(ex) => p.failure(ex)
      case \/-(r) => p.success(r)
    }

    p.future
  }

  /* Future -> Task conversion */
  def scalaFuture2scalazTask[T](fut: => Future[T])(implicit ctx: ExecutionContext): Task[T] = {
    Task.async {
      register =>
        fut.onComplete {
          case Success(v) => register(\/-(v))
          case Failure(ex) => register(-\/(ex))
        }
    }
  }

  /* Future -> Task NaturalTransformation */
  def Task2FutureNT(implicit ctx: ExecutionContext) = new NaturalTransformation[Future, Task] {
    def apply[A](fa: Future[A]): Task[A] = scalaFuture2scalazTask(fa)
  }

  def enumerateSeq[E, A](l: Seq[E], i: Iteratee[E, A])(implicit ctx: ExecutionContext): Future[Iteratee[E, A]] = {
    l.foldLeft(Future.successful(i))((i, e) =>
      i.flatMap(it => it.pureFold {
        case Step.Cont(k) => k(Input.El(e))
        case _ => it
      })
    )
  }


}
