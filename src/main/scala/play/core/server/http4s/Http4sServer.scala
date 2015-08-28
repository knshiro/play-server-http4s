package play.core.server.http4s

import ScalazConversions._

import java.net.{InetAddress, InetSocketAddress}

import org.http4s.server.blaze.BlazeBuilder
import org.http4s.server.{ServerBuilder, Service, HttpService}
import org.http4s.{Response, Request}
import play.api._
import play.api.http.DefaultHttpErrorHandler
import play.api.libs.iteratee.{Enumerator, Iteratee, Done, Input}
import play.api.mvc.{Result, EssentialAction, Handler, RequestHeader}
import play.core.ApplicationProvider
import play.core.server.common.{ServerResultUtils, ForwardedHeaderHandler}
import play.core.server.{Server => PlayServer, _}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Success, Failure, Try}
import scala.util.control.NonFatal
import scalaz.{-\/, \/-}
import scalaz.concurrent.Task

/**
 * creates a Server implementation based Netty
 */
class Http4sServer(
  config: ServerConfig,
  val applicationProvider: ApplicationProvider,
  stopHook: () => Future[Unit])
  extends PlayServer {

  import Http4sServer._

  def mode = config.mode

  val builder: ServerBuilder = BlazeBuilder
  val httpService: HttpService = Service.lift[Request,Response](handleRequest)

  val server = config.port.map { port =>
    builder.bindHttp(port)
      // TODO: Implement support websockets
      //.withWebSockets(true)
      .mountService(httpService, "/")
      .run
  }

  //  TODO: Check for HTTPS support
  //  config.sslPort.map { port =>
  //
  //  }

  // TODO: retrieve those values from the server instance
  override def httpPort: Option[Int] = config.port
  override def httpsPort: Option[Int] = config.sslPort
  override def mainAddress(): InetSocketAddress = ServerBuilder.DefaultSocketAddress

  // Each request needs an id
  private val requestIDs = new java.util.concurrent.atomic.AtomicLong(0)

  private lazy val modelConversion = new Http4sModelConversion(
    new ForwardedHeaderHandler(
      ForwardedHeaderHandler.ForwardedHeaderHandlerConfig(applicationProvider.get.toOption.map(_.configuration))
    )
  )


  def handleRequest(request: Request): Task[Response] = {
    val requestId = requestIDs.incrementAndGet()
    val remoteAddress = request.remote.get
    val (convertedRequestHeader, requestBodySource) = modelConversion.convertRequest(
      requestId = requestId,
      remoteAddress = remoteAddress,
      secureProtocol = false, // TODO: Change value once HTTPS connections are supported
      request = request)
    val (taggedRequestHeader, handler, newTryApp) = getHandler(convertedRequestHeader)

    executeHandler(
      newTryApp,
      request,
      taggedRequestHeader,
      requestBodySource,
      handler
    )
  }

  private def getHandler(requestHeader: RequestHeader): (RequestHeader, Handler, Try[Application]) = {
    import play.api.libs.iteratee.Execution.Implicits.trampoline
    getHandlerFor(requestHeader) match {
      case Left(futureResult) =>
        (
          requestHeader,
          EssentialAction(_ => Iteratee.flatten(futureResult.map(result => Done(result, Input.Empty)))),
          Failure(new Exception("getHandler returned Result, but not Application"))
          )
      case Right((newRequestHeader, handler, newApp)) =>
        (
          newRequestHeader,
          handler,
          Success(newApp) // TODO: Change getHandlerFor to use the app that we already had
          )
    }
  }

  private def executeHandler(
    tryApp: Try[Application],
    request: Request,
    taggedRequestHeader: RequestHeader,
    requestBodyEnumerator: Enumerator[Array[Byte]],
    handler: Handler): Task[Response] = handler match {
    //execute normal action
    case action: EssentialAction =>
      val actionWithErrorHandling = EssentialAction { rh =>
        import play.api.libs.iteratee.Execution.Implicits.trampoline
        Iteratee.flatten(action(rh).unflatten.map(_.it).recover {
          case error =>
            Iteratee.flatten(
              handleHandlerError(tryApp, taggedRequestHeader, error).map(result => Done(result, Input.Empty))
            ): Iteratee[Array[Byte], Result]
        })
      }
      executeAction(tryApp, request, taggedRequestHeader, requestBodyEnumerator, actionWithErrorHandling)
    case unhandled => sys.error(s"AkkaHttpServer doesn't handle Handlers of this type: $unhandled")
  }

  def executeAction(
    tryApp: Try[Application],
    request: Request,
    taggedRequestHeader: RequestHeader,
    requestBodyEnumerator: Enumerator[Array[Byte]],
    action: EssentialAction): Task[Response] = {

    import play.api.libs.iteratee.Execution.Implicits.trampoline
    val actionIteratee: Iteratee[Array[Byte], Result] = action(taggedRequestHeader)
    val resultFuture: Task[Result] = scalaFuture2scalazTask(requestBodyEnumerator |>>> actionIteratee)

    val responseTask: Task[Response] = resultFuture.flatMap { result =>
      val cleanedResult: Result = ServerResultUtils.cleanFlashCookie(taggedRequestHeader, result)
      modelConversion.convertResult(taggedRequestHeader, cleanedResult, request.httpVersion)
    }
    responseTask
  }

  /** Error handling to use during execution of a handler (e.g. an action) */
  private def handleHandlerError(tryApp: Try[Application], rh: RequestHeader, t: Throwable): Future[Result] = {
    tryApp match {
      case Success(app) => app.errorHandler.onServerError(rh, t)
      case Failure(_) => DefaultHttpErrorHandler.onServerError(rh, t)
    }
  }

  override def stop() {

    // Now shut the application down
    applicationProvider.current.foreach(Play.stop)

    try {
      super.stop()
    } catch {
      case NonFatal(e) => logger.error("Error while stopping logger", e)
    }

    mode match {
      case Mode.Test =>
      case _ => logger.info("Stopping server...")
    }

    // First, close all opened sockets
    server.foreach { s =>
      s.shutdownNow()
    }

    // Call provided hook
    // Do this last because the hooks were created before the server,
    // so the server might need them to run until the last moment.
    Await.result(stopHook(), Duration.Inf)
  }

}


/**
 * Bootstraps Play application with a NettyServer backend.
 */
object Http4sServer {

  private val logger = Logger(this.getClass)

  /**
   * A ServerProvider for creating an AkkaHttpServer.
   */
  implicit val provider = new Http4sServerProvider
}

/**
 * Knows how to create an AkkaHttpServer.
 */
class Http4sServerProvider extends ServerProvider {
  def createServer(context: ServerProvider.Context) =
    new Http4sServer(context.config, context.appProvider, context.stopHook)
}