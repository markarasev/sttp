package sttp.client

import java.nio.ByteBuffer

import org.scalajs.dom.FormData
import org.scalajs.dom.experimental.{
  BodyInit,
  Fetch,
  HttpMethod,
  RequestCredentials,
  RequestInit,
  RequestMode,
  RequestRedirect,
  ResponseType,
  Headers => JSHeaders,
  Request => FetchRequest,
  Response => FetchResponse
}
import org.scalajs.dom.raw.{Blob, BlobPropertyBag}
import sttp.capabilities.{Effect, Streams}
import sttp.client.dom.experimental.{AbortController, FilePropertyBag, File => DomFile}
import sttp.client.internal.{SttpFile, _}
import sttp.monad.MonadError
import sttp.monad.syntax._
import sttp.model.{Header, StatusCode}

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.Promise
import scala.scalajs.js.timers._
import scala.scalajs.js.typedarray._

object FetchOptions {
  val Default = FetchOptions(
    credentials = None,
    mode = None
  )
}

final case class FetchOptions(
    credentials: Option[RequestCredentials],
    mode: Option[RequestMode]
)

/**
  * A backend that uses the `fetch` JavaScript api.
  *
  * @see https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API
  */
abstract class AbstractFetchBackend[F[_], S <: Streams[S], P](
    options: FetchOptions,
    customizeRequest: FetchRequest => FetchRequest
)(
    monad: MonadError[F]
) extends SttpBackend[F, P] {
  override implicit def responseMonad: MonadError[F] = monad

  val streams: Streams[S]
  type PE = P with Effect[F]

  override def send[T, R >: PE](request: Request[T, R]): F[Response[T]] = {
    // https://stackoverflow.com/q/31061838/4094860
    val readTimeout = request.options.readTimeout
    val (signal, cancelTimeout) = readTimeout match {
      case timeout: FiniteDuration =>
        val controller = new AbortController()
        val signal = controller.signal

        val timeoutHandle = setTimeout(timeout) {
          controller.abort()
        }
        (Some(signal), () => clearTimeout(timeoutHandle))

      case _ =>
        (None, () => ())
    }

    val rheaders = new JSHeaders()
    request.headers.foreach {
      case Header(name, value) =>
        rheaders.set(name, value)
    }

    val req = createBody(request.body).map { rbody =>
      // use manual so we can return a specific error instead of the generic "TypeError: Failed to fetch"
      val rredirect = if (request.options.followRedirects) RequestRedirect.follow else RequestRedirect.manual

      val requestInitStatic = new RequestInit() {
        method = request.method.method.asInstanceOf[HttpMethod]
        headers = rheaders
        body = rbody
        referrer = js.undefined
        referrerPolicy = js.undefined
        mode = options.mode.orUndefined
        credentials = options.credentials.orUndefined
        cache = js.undefined
        redirect = rredirect
        integrity = js.undefined
        keepalive = js.undefined
        signal = js.undefined
        window = js.undefined
      }

      val requestInitDynamic = requestInitStatic.asInstanceOf[js.Dynamic]
      signal.foreach(s => requestInitDynamic.updateDynamic("signal")(s))
      requestInitDynamic.updateDynamic("redirect")(rredirect) // named wrong in RequestInit
      val requestInit = requestInitDynamic.asInstanceOf[RequestInit]

      new FetchRequest(request.uri.toString, requestInit)
    }

    val result = req
      .flatMap { r => transformPromise(Fetch.fetch(customizeRequest(r))) }
      .flatMap { resp =>
        if (resp.`type` == ResponseType.opaqueredirect) {
          responseMonad.error[FetchResponse](new RuntimeException("Unexpected redirect"))
        } else {
          responseMonad.unit(resp)
        }
      }
      .flatMap { resp =>
        val headers = convertResponseHeaders(resp.headers)
        val metadata = ResponseMetadata(headers, StatusCode(resp.status), resp.statusText)

        val body: F[T] = readResponseBody(resp, request.response, metadata)

        body.map { b =>
          Response[T](
            body = b,
            code = StatusCode(resp.status),
            statusText = resp.statusText,
            headers = headers,
            history = Nil
          )
        }
      }
    addCancelTimeoutHook(result, cancelTimeout)
  }

  protected def addCancelTimeoutHook[T](result: F[T], cancel: () => Unit): F[T]

  private def convertResponseHeaders(headers: JSHeaders): Seq[Header] = {
    headers
      .jsIterator()
      .toIterator
      .flatMap { hs =>
        // this will only ever be 2 but the types dont enforce that
        if (hs.length >= 2) {
          val name = hs(0)
          hs.toList.drop(1).map(v => Header(name, v))
        } else {
          Seq.empty
        }
      }
      .toList
  }

  private def createBody[R >: PE](body: RequestBody[R]): F[js.UndefOr[BodyInit]] = {
    body match {
      case NoBody =>
        responseMonad.unit(js.undefined) // skip

      case b: BasicRequestBody =>
        responseMonad.unit(writeBasicBody(b))

      case StreamBody(s) =>
        handleStreamBody(s.asInstanceOf[streams.BinaryStream])

      case mp: MultipartBody[_] =>
        val formData = new FormData()
        mp.parts.foreach { part =>
          val value = part.body match {
            case NoBody                 => Array[Byte]().toTypedArray.asInstanceOf[BodyInit]
            case body: BasicRequestBody => writeBasicBody(body)
            case StreamBody(_)          => throw new IllegalArgumentException("Streaming multipart bodies are not supported")
            case MultipartBody(_)       => throwNestedMultipartNotAllowed
          }
          // the only way to set the content type is to use a blob
          val blob =
            value match {
              case b: Blob => b
              case v =>
                new Blob(Array(v.asInstanceOf[js.Any]).toJSArray, BlobPropertyBag(part.contentType.orUndefined))
            }
          part.fileName match {
            case None           => formData.append(part.name, blob)
            case Some(fileName) => formData.append(part.name, blob, fileName)
          }
        }
        responseMonad.unit(formData)
    }
  }

  private def writeBasicBody(body: BasicRequestBody): BodyInit = {
    body match {
      case StringBody(b, encoding, _) =>
        if (encoding.compareToIgnoreCase(Utf8) == 0) b
        else b.getBytes(encoding).toTypedArray.asInstanceOf[BodyInit]

      case ByteArrayBody(b, _) =>
        b.toTypedArray.asInstanceOf[BodyInit]

      case ByteBufferBody(b, _) =>
        if (b.isReadOnly) cloneByteBuffer(b).array().toTypedArray.asInstanceOf[BodyInit]
        else b.array().toTypedArray.asInstanceOf[BodyInit]

      case InputStreamBody(is, _) =>
        toByteArray(is).toTypedArray.asInstanceOf[BodyInit]

      case FileBody(f, _) =>
        f.toDomFile
    }
  }

  // https://stackoverflow.com/questions/3366925/deep-copy-duplicate-of-javas-bytebuffer
  private def cloneByteBuffer(original: ByteBuffer): ByteBuffer = {
    val clone =
      if (original.isDirect) ByteBuffer.allocateDirect(original.capacity)
      else ByteBuffer.allocate(original.capacity)
    val readOnlyCopy = original.asReadOnlyBuffer
    readOnlyCopy.rewind
    clone
      .put(original)
      .flip
      .position(original.position)
      .limit(original.limit)
      .asInstanceOf[ByteBuffer]
      .order(original.order)
  }

  protected def handleStreamBody(s: streams.BinaryStream): F[js.UndefOr[BodyInit]]

  private def readResponseBody[T, R >: PE](
      response: FetchResponse,
      responseAs: ResponseAs[T, R],
      meta: ResponseMetadata
  ): F[T] = {
    responseAs match {
      case MappedResponseAs(raw, g) =>
        readResponseBody(response, raw, meta).map(t => g(t, meta))

      case raf: ResponseAsFromMetadata[T, R] =>
        readResponseBody(response, raf(meta), meta)

      case IgnoreResponse =>
        transformPromise(response.arrayBuffer()).map(_ => ())

      case ResponseAsByteArray =>
        responseToByteArray(response).map(b => b) // adjusting type because ResponseAs is covariant

      case ResponseAsFile(file) =>
        transformPromise(response.arrayBuffer()).map { ab =>
          SttpFile.fromDomFile(
            new DomFile(
              Array(ab.asInstanceOf[js.Any]).toJSArray,
              file.name,
              FilePropertyBag(`type` = file.toDomFile.`type`)
            )
          )
        }

      case _: ResponseAsStreamUnsafe[_, _] =>
        handleResponseAsStream(response).map(_._1).asInstanceOf[F[T]]

      case ResponseAsStream(_, f) =>
        handleResponseAsStream(response).flatMap {
          case (stream, cancel) =>
            f.asInstanceOf[streams.BinaryStream => F[T]](stream).ensure(cancel())
        }
    }
  }

  private def responseToByteArray(response: FetchResponse) = {
    transformPromise(response.arrayBuffer()).map { ab => new Int8Array(ab).toArray }
  }

  protected def handleResponseAsStream(response: FetchResponse): F[(streams.BinaryStream, () => F[Unit])]

  override def close(): F[Unit] = monad.unit(())

  protected def transformPromise[T](promise: => Promise[T]): F[T]
}
