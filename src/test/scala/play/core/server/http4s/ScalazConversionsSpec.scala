package play.core.server.http4s

import org.specs2.mutable.Specification
import play.api.libs.iteratee.{Iteratee, Enumerator}
import play.core.server.http4s.ScalazConversions._

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scalaz.concurrent.Task
import scalaz.stream.Cause.Kill
import scala.concurrent.ExecutionContext.Implicits.global
import scalaz.stream._

/**
 * Created by Ugo Bataillard on 8/30/15.
 */
class ScalazConversionsSpec extends Specification {


  "ScalazConversions.process" should {
    "convert a simple Enumerator to a Process" in {

      val inputs = (1 to 100).toList

      val outputs = process(Enumerator(inputs: _*)).run.runLog.run

      outputs must containTheSameElementsAs(inputs)
    }

    "bubble up an exception" in {

      def erroEnumerator[E] = new Enumerator[E] {
        def apply[A](i: Iteratee[E, A]) = Future.failed(new Exception("AAAAAAA"))
      }

      val inputs = (1 to 100).toList

      val proc = process(Enumerator(inputs: _*) >>> erroEnumerator ).run

      proc.runLog.run must throwA[Exception].like {
        case e:Exception => e.getMessage must_== "AAAAAAA"
      }

    }
  }

  "ScalazConversions.enumerator" should {
    "convert an Emit Process to an Enumerator" in {
      val inputs = (1 to 100).toList
      val outputs = Await.result(enumerator(Process.emitAll(inputs)) |>>> Iteratee.fold(List.empty[Int])((total,elt) => elt :: total), 10.seconds)
      outputs must containTheSameElementsAs (inputs)
    }

    "convert an Eval Process to an Enumerator" in {
      val inputs = List.fill(100)(10)

      val proc = Process.repeatEval(Task.now(10)).take(100)

      val outputs = Await.result(enumerator(proc) |>>> Iteratee.fold(List.empty[Int])((total,elt) => elt :: total), 10.seconds)
      outputs must containTheSameElementsAs (inputs)
    }

    "convert an Append Process to an Enumerator" in {
      val inputs = List.fill(300)(10)

      val proc = Process.repeatEval(Task.now(10)).take(100) ++ Process.emitAll(List.fill(100)(10)) ++ Process.repeatEval(Task.now(10)).take(100)

      val outputs = Await.result(enumerator(proc) |>>> Iteratee.fold(List.empty[Int])((total,elt) => elt :: total), 10.seconds)
      outputs must containTheSameElementsAs (inputs)
    }

    "bubble up exceptions" in {
      val proc = Process.repeatEval(Task.now(10)).take(100) ++ Process.fail(new Exception("aha")) ++ Process.repeatEval(Task.now(10)).take(100)

      val futureResult = enumerator(proc) |>>> Iteratee.fold(List.empty[Int])((total,elt) => elt :: total)

      Await.result(futureResult, 10.seconds) must throwA[Exception].like {
        case e:Exception => e.getMessage must_== "aha"
      }

    }

    // NEED TO BE VERIFIED
    "bubble up kill as an exception" in {
      val proc = Process.repeatEval(Task.now(10)).take(100) ++ Process.Halt(Kill) ++ Process.repeatEval(Task.now(10)).take(100)

      val futureResult = enumerator(proc) |>>> Iteratee.fold(List.empty[Int])((total,elt) => elt :: total)

      Await.result(futureResult, 10.seconds) must throwA[Exception]

    }


  }
}
