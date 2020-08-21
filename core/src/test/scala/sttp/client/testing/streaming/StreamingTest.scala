package sttp.client.testing.streaming

import sttp.client._
import sttp.client.testing.{ConvertToFuture, ToFutureWrapper}
import org.scalatest.{BeforeAndAfterAll, SuiteMixin}
import org.scalatest.freespec.AsyncFreeSpecLike
import StreamingTest._
import sttp.client.internal.Utf8
import org.scalatest.matchers.should.Matchers
import sttp.capabilities.{Effect, Streams}
import sttp.client.testing.HttpTest.endpoint

// TODO: change to `extends AsyncFreeSpec` when https://github.com/scalatest/scalatest/issues/1802 is fixed
abstract class StreamingTest[F[_], S]
    extends SuiteMixin
    with AsyncFreeSpecLike
    with Matchers
    with ToFutureWrapper
    with BeforeAndAfterAll
    with StreamingTestExtensions[F, S] {

  val streams: Streams[S]

  def backend: SttpBackend[F, S]

  implicit def convertToFuture: ConvertToFuture[F]

  def bodyProducer(chunks: Iterable[Array[Byte]]): streams.BinaryStream

  private def stringBodyProducer(body: String): streams.BinaryStream =
    bodyProducer(body.getBytes(Utf8).grouped(10).toIterable)

  def bodyConsumer(stream: streams.BinaryStream): F[String]

  protected def supportsStreamingMultipartParts = true

  "stream request body" in {
    basicRequest
      .post(uri"$endpoint/streaming/echo")
      .streamBody(streams)(stringBodyProducer(Body))
      .send(backend)
      .toFuture()
      .map { response =>
        response.body shouldBe Right(Body)
      }
  }

  "stream large request body" in {
    basicRequest
      .post(uri"$endpoint/streaming/echo")
      .streamBody(streams)(stringBodyProducer(Body))
      .send(backend)
      .toFuture()
      .map { response =>
        response.body shouldBe Right(Body)
      }
  }

  "receive a stream" in {
    // TODO: for some reason these explicit types are needed in Dotty
    val r0: RequestT[Identity, String, Effect[F] with S] = basicRequest
      .post(uri"$endpoint/streaming/echo")
      .body(Body)
      .response(asStreamAlways(streams)(bodyConsumer(_)))
    r0.send(backend)
      .toFuture()
      .map { response =>
        response.body shouldBe Body
      }
  }

  "receive a stream and ignore it (without consuming)" in {
    // TODO: for some reason these explicit types are needed in Dotty
    val r0: RequestT[Identity, String, Effect[F] with S] = basicRequest
      .post(uri"$endpoint/streaming/echo")
      .body(Body)
      // if the backend has any, mechanisms to consume an incorrectly handled (ignored) stream should kick in
      .response(asStreamAlways(streams)(_ => bodyConsumer(stringBodyProducer("ignore"))))
    r0.send(backend)
      .toFuture()
      .map { response =>
        response.body shouldBe "ignore"
      }
  }

  "receive a stream (unsafe)" in {
    // TODO: for some reason these explicit types are needed in Dotty
    val r0: RequestT[Identity, streams.BinaryStream, S] = basicRequest
      .post(uri"$endpoint/streaming/echo")
      .body(Body)
      .response(asStreamAlwaysUnsafe(streams))
    r0.send(backend)
      .toFuture()
      .flatMap { response =>
        bodyConsumer(response.body).toFuture()
      }
      .map { responseBody =>
        responseBody shouldBe Body
      }
  }

  "receive a large stream (unsafe)" in {
    // TODO: for some reason these explicit types are needed in Dotty
    val r0: RequestT[Identity, streams.BinaryStream, S] = basicRequest
      .post(uri"$endpoint/streaming/echo")
      .body(LargeBody)
      .response(asStreamAlwaysUnsafe(streams))
    r0.send(backend)
      .toFuture()
      .flatMap { response =>
        bodyConsumer(response.body).toFuture()
      }
      .map { responseBody =>
        if (responseBody.length != LargeBody.length) {
          fail(s"Response body had length ${responseBody.length}, instead of ${LargeBody.length}, starts with: ${responseBody
            .take(512)}")
        } else {
          succeed
        }
      }
  }

  "receive a stream or error (unsafe)" in {
    // TODO: for some reason these explicit types are needed in Dotty
    val r0: RequestT[Identity, Either[String, streams.BinaryStream], S] = basicRequest
      .post(uri"$endpoint/streaming/echo")
      .body(Body)
      .response(asStreamUnsafe(streams))
    r0.send(backend)
      .toFuture()
      .flatMap { response =>
        bodyConsumer(response.body.right.get).toFuture()
      }
      .map { responseBody =>
        responseBody shouldBe Body
      }
  }

  "receive a mapped stream (unsafe)" in {
    // TODO: for some reason these explicit types are needed in Dotty
    val r0: RequestT[Identity, (streams.BinaryStream, Boolean), S] = basicRequest
      .post(uri"$endpoint/streaming/echo")
      .body(Body)
      .response(asStreamAlwaysUnsafe(streams).map(s => (s, true)))
    r0
      .send(backend)
      .toFuture()
      .flatMap { response =>
        val (stream, flag) = response.body
        bodyConsumer(stream).toFuture().map((_, flag))
      }
      .map { responseBody =>
        responseBody shouldBe ((Body, true))
      }
  }

  "receive a stream from an https site (unsafe)" in {
    val numChunks = 100
    val url = uri"https://httpbin.org/stream/$numChunks"

    // TODO: for some reason these explicit types are needed in Dotty
    val r0: RequestT[Identity, streams.BinaryStream, S] = basicRequest
      // of course, you should never rely on the internet being available
      // in tests, but that's so much easier than setting up an https
      // testing server
      .get(url)
      .response(asStreamAlwaysUnsafe(streams))

    r0
      .send(backend)
      .toFuture()
      .flatMap { response =>
        bodyConsumer(response.body).toFuture()
      }
      .map { responseBody =>
        val urlRegex = s""""${url.toString}"""".r

        urlRegex.findAllIn(responseBody).length shouldBe numChunks
      }
  }

  if (supportsStreamingMultipartParts) {
    "send a stream part in a multipart request" in {
      basicRequest
        .post(uri"$endpoint/multipart")
        .response(asStringAlways)
        .multipartBody(
          multipart("p1", "v1"),
          multipartStream(streams)("p2", stringBodyProducer("v2")),
          multipart("p3", "v3")
        )
        .send(backend)
        .toFuture()
        .map { response =>
          response.body shouldBe s"p1=v1, p2=v2, p3=v3"
        }
    }
  }

  override protected def afterAll(): Unit = {
    backend.close().toFuture()
    super.afterAll()
  }

}

object StreamingTest {
  val Body = "streaming test"
  val LargeBody: String = "x" * 4000000
}
