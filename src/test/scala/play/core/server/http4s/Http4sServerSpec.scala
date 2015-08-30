package play.core.server.http4s

import akka.util.Timeout
import play.api.libs.EventSource
import play.api.libs.iteratee.{Concurrent, Iteratee}
import play.api.libs.ws._
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Results._
import play.api.mvc._
import play.api.test._

import scala.concurrent.Future

/**
 * Created by Ugo Bataillard on 8/28/15.
 */
object Http4sServerSpec extends PlaySpecification with WsTestClient {

  sequential

  def requestFromServer[T](
    path: String)(
    exec: WSRequest => Future[WSResponse])(
    routes: PartialFunction[(String, String), Handler])(
    check: WSResponse => T)(
    implicit awaitTimeout: Timeout): T = {
    running(TestServer(testServerPort, FakeApplication(withRoutes = routes), serverProvider = Some(Http4sServer.provider))) {
      val plainRequest = wsUrl(path)(testServerPort)
      val responseFuture = exec(plainRequest)
      val response = await(responseFuture)(awaitTimeout)
      check(response)
    }
  }

  "Http4sServer" should {

    "send hello world" in {
      // This test experiences CI timeouts. Give it more time.
      val reallyLongTimeout = Timeout(defaultAwaitTimeout.duration * 3)
      requestFromServer("/hello") { request =>
        request.get()
      } {
        case ("GET", "/hello") => Action(Ok("greetings"))
      } { response =>
        response.body must_== "greetings"
      }(reallyLongTimeout)
    }

    "send responses when missing a Content-Length" in {
      requestFromServer("/hello") { request =>
        request.get()
      } {
        case ("GET", "/hello") => Action(Ok("greetings"))
      } { response =>
        response.status must_== 200
        response.header(CONTENT_TYPE) must_== Some("text/plain; charset=utf-8")
        response.header(CONTENT_LENGTH) must_== Some("9")
        response.header(TRANSFER_ENCODING) must_== None
        response.body must_== "greetings"
      }
    }

    "not send chunked responses when given a Content-Length" in {
      requestFromServer("/hello") { request =>
        request.get()
      } {
        case ("GET", "/hello") => Action {
          Ok("greetings").withHeaders(CONTENT_LENGTH -> "9")
        }
      } { response =>
        response.status must_== 200
        response.header(CONTENT_TYPE) must_== Some("text/plain; charset=utf-8")
        response.header(CONTENT_LENGTH) must_== Some("9")
        response.header(TRANSFER_ENCODING) must_== None
        response.body must_== "greetings"
      }
    }

    def headerDump(headerNames: String*)(implicit request: Request[_]): String = {
      val headerGroups: Seq[String] = for (n <- headerNames) yield {
        val headerGroup = request.headers.getAll(n)
        headerGroup.mkString("<", ", ", ">")
      }
      headerGroups.mkString("; ")
    }

    "pass request headers to Actions" in {
      requestFromServer("/abc") { request =>
        request.withHeaders(
          ACCEPT_ENCODING -> "utf-8",
          ACCEPT_LANGUAGE -> "en-NZ").get()
      } {
        case ("GET", "/abc") => Action { implicit request =>
          Ok(headerDump(ACCEPT_ENCODING, ACCEPT_LANGUAGE))
        }
      } { response =>
        response.status must_== 200
        response.body must_== "<utf-8>; <en-NZ>"
      }
    }

    "pass POST request bodies to Actions" in {
      requestFromServer("/greet") { request =>
        request.post("Bob")
      } {
        case ("POST", "/greet") => Action(parse.text) { implicit request =>
          val name = request.body
          Ok(s"Hello $name")
        }
      } { response =>
        response.status must_== 200
        response.body must_== "Hello Bob"
      }
    }

    "send response status" in {
      requestFromServer("/def") { request =>
        request.get()
      } {
        case ("GET", "/abc") => Action { implicit request =>
          ???
        }
      } { response =>
        response.status must_== 404
      }
    }

    val httpServerTagRoutes: PartialFunction[(String, String), Handler] = {
      case ("GET", "/httpServerTag") => Action { implicit request =>
        val httpServer = request.tags.get("HTTP_SERVER")
        Ok(httpServer.toString)
      }
    }

    "pass tag of HTTP_SERVER->akka-http to Actions" in {
      requestFromServer("/httpServerTag") { request =>
        request.get()
      } {
        case ("GET", "/httpServerTag") => Action { implicit request =>
          val httpServer = request.tags.get("HTTP_SERVER")
          Ok(httpServer.toString)
        }
      } { response =>
        response.status must_== 200
        response.body must_== "Some(http4s)"
      }
    }

    "support WithServer form" in new WithServer(
      app = FakeApplication(withRoutes = httpServerTagRoutes),
      serverProvider = Some(Http4sServer.provider)) {
      val response = await(wsUrl("/httpServerTag").get())
      response.status must equalTo(OK)
      response.body must_== "Some(http4s)"
    }

    "start and stop cleanly" in {
      PlayRunners.mutex.synchronized {
        def testStartAndStop(i: Int) = {
          val resultString = s"result-$i"
          val app = FakeApplication(withRoutes = {
            case ("GET", "/") => Action(Ok(resultString))
          })
          val server = TestServer(testServerPort, app, serverProvider = Some(Http4sServer.provider))
          server.start()
          try {
            val response = await(wsUrl("/")(testServerPort).get())
            response.body must_== resultString
          } finally {
            server.stop()
          }
        }
        // Start and stop the server 20 times
        (0 until 20) must contain { (i: Int) => testStartAndStop(i) }
      }
    }

    "Handle SSE" in {

      import scala.concurrent.ExecutionContext.Implicits.global
      val list = (1 to 10).map("Chunk number: " + _.toString).toList

      val (chatOut, chatChannel) = Concurrent.broadcast[String]

      val routes: PartialFunction[(String, String), Handler]  = {
        case ("GET", "/") => Action(
          Ok.feed(chatOut
            &> EventSource()
          ).as("text/event-stream")
        )
      }

      running(TestServer(testServerPort, FakeApplication(withRoutes = routes), serverProvider = Some(Http4sServer.provider))) {
        val plainRequest = wsUrl("/")(testServerPort)
        val responseFuture = plainRequest.stream()

        Future {
          Thread.sleep(100)
          list.foreach { s =>
            chatChannel.push(s)
            Thread.sleep(100)
          }
          chatChannel.eofAndEnd()
        }

        val (responseHeaders, stream) = await(responseFuture)

        println("Headers: " + responseHeaders)
        val res = await(stream.map { bs => Seq(new String(bs)) } |>>> Iteratee.consume[Seq[String]]())
        res must_== list.map("data: " + _ + "\n\n")
      }

    }

  }
}