package play.core.server.http4s

import java.net.InetSocketAddress

import org.http4s.headers.{Connection, `Transfer-Encoding`}
import org.http4s.{Headers => Http4sHeaders, _}
import play.api.Logger
import play.api.libs.iteratee.Enumerator
import play.api.mvc.{Headers => PlayHeaders, RequestHeader, Result, Results}
import play.core.server.common.{ForwardedHeaderHandler, ServerRequestUtils, ServerResultUtils}
import play.core.server.http4s.ScalazConversions._
import scodec.bits.ByteVector

import scala.concurrent.Future
import scalaz.stream._
import scalaz.concurrent.Task

//TODO REMOVE

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Created by Ugo Bataillard on 8/25/15.
 */

private[server] class Http4sModelConversion(forwardedHeaderHandler: ForwardedHeaderHandler) {

  private val logger = Logger(classOf[Http4sModelConversion])

  def convertRequest(requestId: Long,
    remoteAddress: InetSocketAddress,
    secureProtocol: Boolean,
    request: Request): (RequestHeader, Enumerator[Array[Byte]]) = {

    (
      createRequestHeader(request, requestId, remoteAddress, secureProtocol),
      convertRequestBody(request)
      )
  }

  /** Create the request header */
  private def createRequestHeader(
    request: Request,
    requestId: Long,
    _remoteAddress: InetSocketAddress,
    secureProtocol: Boolean): RequestHeader = {

    new RequestHeader {
      override val id = requestId
      override val tags = Map("HTTP_SERVER" -> "http4s")
      override def uri = request.uri.toString()
      override def path = request.uri.path
      override def method = request.method.name
      override def version = request.httpVersion.toString()
      override def queryString = request.multiParams
      override lazy val headers = getHeaders(request)
      override lazy val remoteAddress = {
        ServerRequestUtils.findRemoteAddress(
          forwardedHeaderHandler,
          headers,
          connectionRemoteAddress = _remoteAddress)
      }
      override lazy val secure = {
        ServerRequestUtils.findSecureProtocol(
          forwardedHeaderHandler,
          headers,
          connectionSecureProtocol = secureProtocol
        )
      }
    }
  }


  /** Convert the Netty headers to a Play headers object. */
  private def getHeaders(request: Request): PlayHeaders = {
    val pairs = request.headers.toList.map(h => h.name.value -> h.value)
    new PlayHeaders(pairs)
  }

  private def convertRequestBody(request: Request): Enumerator[Array[Byte]] = {
    enumerator(request.body).map(_.toArray)
  }


  /**
   * Convert a Play `Result` object into an Akka `HttpResponse` object.
   */
  def convertResult(
    requestHeader: RequestHeader,
    result: Result,
    httpVersion: HttpVersion): Task[Response] = {

    scalaFuture2scalazTask(ServerResultUtils.determineResultStreaming(requestHeader, result)).flatMap {
      case Left(ServerResultUtils.InvalidResult(reason, alternativeResult)) =>
        logger.warn(s"Cannot send result, sending error result instead: $reason")
        convertResult(requestHeader, alternativeResult, httpVersion)

      case Right((streaming, connectionHeader)) =>

        val response = createHttp4sResponse(requestHeader, result, connectionHeader, httpVersion)

        def streamEnum(response: Response, enum: Enumerator[Array[Byte]], chunked: Boolean = true): Task[Response] = {
          val dataEnum: Enumerator[ByteVector] = enum.map(ByteVector(_)) >>> Enumerator.eof
          process(dataEnum) map { entityBody =>
            val r = response.copy(body = entityBody)
            if (chunked) {
              r.putHeaders(`Transfer-Encoding`(TransferCoding.chunked))
            } else r
          }
        }

        streaming match {
          case ServerResultUtils.StreamWithClose(enum) =>
            assert(connectionHeader.willClose)
            streamEnum(response, enum)
          case ServerResultUtils.StreamWithNoBody =>
            Task.now(response)
          case ServerResultUtils.StreamWithKnownLength(enum) =>
            streamEnum(response, enum, chunked = false)
          case ServerResultUtils.StreamWithStrictBody(body) =>
            Task.now(response.copy(body = Process.eval(Task.now(ByteVector(body)))))
          case ServerResultUtils.UseExistingTransferEncoding(enum) =>
            streamEnum(response, enum)
          case ServerResultUtils.PerformChunkedTransferEncoding(transferEncodedEnum) =>
            streamEnum(response
              .putHeaders(`Transfer-Encoding`(TransferCoding.chunked)), transferEncodedEnum &> Results
              .chunk)
        }
    }
  }

  def createHttp4sResponse(requestHeader: RequestHeader, result: Result, connectionHeader: ServerResultUtils.ConnectionHeader, httpVersion: HttpVersion): Response = {

    import org.http4s.Http4s._

    val convertedHeaders: Http4sHeaders =
      (connectionHeader.header map { headerValue =>
        Connection("close".ci)
      }) ++
        convertResponseHeaders(result.header.headers)
    val responseStatus = result.header.reasonPhrase match {
      case Some(phrase) => Status.fromIntAndReason(result.header.status, phrase)
        .getOrElse(throw new IllegalArgumentException)
      case None => Status.fromInt(result.header.status).getOrElse(throw new IllegalArgumentException)
    }

    Response(
      status = responseStatus,
      httpVersion = httpVersion,
      headers = convertedHeaders)
  }


  /**
   * Convert Play response headers into http4s `Headers` object
   */
  private def convertResponseHeaders(
    playHeaders: Map[String, String]): Http4sHeaders = {
    val rawHeaders: Iterable[(String, String)] = ServerResultUtils.splitSetCookieHeaders(playHeaders)

    val convertedHeaders: List[Header] = rawHeaders.map {
      case (name, value) => Header(name, value)
    }.toList
    Http4sHeaders(convertedHeaders)
  }

}

