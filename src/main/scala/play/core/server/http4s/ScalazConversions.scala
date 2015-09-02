package play.core.server.http4s

import play.api.libs.iteratee._

import scala.annotation.tailrec
import scala.concurrent._
import scala.util.{Failure, Success}
import scalaz.concurrent.Task
import scalaz.stream._
import scalaz.{-\/, NaturalTransformation, \/-}
import scala.language.higherKinds

/**
 * Created by Ugo Bataillard on 8/25/15.
 */
object ScalazConversions {

  def process[O](enum: Enumerator[O])(implicit ctx: ExecutionContext): Task[Process[Task, O]] = {
    // TODO: Check if this should be synchronous
    Task {
      val q = async.unboundedQueue[O]
      (enum |>>> Iteratee.foreach(q.enqueueOne(_).run))
        .onComplete {
          case Success(_) => q.close.run
          case Failure(t) => q.fail(t).run
        }
      q.dequeue
    }
  }

  /**
   * Helper to wrap evaluation of `p` that may cause side-effects by throwing exception.
   * From https://github.com/scalaz/scalaz-stream/blob/master/src/main/scala/scalaz/stream/Util.scala
   */
  def Try[F[_], A](p: => Process[F, A]): Process[F, A] =
    try p
    catch {case e: Throwable => Process.fail(e)}


  /** Creates an enumerator from a Process[Task, O]
    * From https://github.com/mandubian/playzstream/blob/master/app/models/stream.scala
    * Also inspired by
    * https://github.com/http4s/http4s/blob/54fd3f7a08d525e7c4decb612b1ce229c6c6c4d0/blaze-core/src/main/scala/org/http4s/blaze/util/ProcessWriter.scala
    * */
  def enumerator[O](p: Process[Task, O])(implicit ctx: ExecutionContext) = new Enumerator[O] {

    private type StackElem = Cause => Process.Trampoline[Process[Task, O]]

    def apply[A](it: Iteratee[O, A]): Future[Iteratee[O, A]] = {

      def step(curP: Process[Task, O], curIt: Iteratee[O, A], stack: List[StackElem]): Future[Iteratee[O, A]] = {

        curIt.fold {
          case Step.Done(a, e) =>
            Future.successful(Done(a, e))

          case Step.Cont(k) =>

            curP match {

              case Process.Await(req, recv) =>
                scalazTask2scalaFuture(req.attempt).flatMap { r =>
                  step(Try(recv(Cause.EarlyCause.fromTaskResult(r)).run), curIt, stack)
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
                enumerateSeq(h, Cont(k)).flatMap { i => step(Process.Halt(Cause.End), i, stack) }

              case Process.Append(head, tail) =>
                @tailrec // avoid as many intermediates as possible
                def prepend(i: Int, stack: List[StackElem]): List[StackElem] = {
                  if (i >= 0) prepend(i - 1, tail(i) :: stack)
                  else stack
                }
                step(head, curIt, prepend(tail.length - 1, stack))

              case Process.Halt(cause) if stack.nonEmpty =>
                step(Try(stack.head(cause).run), curIt, stack.tail)

              // Rest are terminal cases
              case Process.Halt(Cause.End) =>
                Future.successful(k(Input.EOF))

              case Process.Halt(Cause.Kill) =>
                Future.failed(new Exception("Stream killed before end"))

              case Process.Halt(Cause.Error(t)) =>
                Future.failed(t)
            }

          case Step.Error(msg, e) =>
            Future.successful(Error(msg, e))
        }
      }

      step(p, it, List.empty)
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
