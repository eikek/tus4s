package http4stus.server

import cats.data.NonEmptyList
import cats.effect.*

import http4stus.Endpoint
import http4stus.data.*
import http4stus.protocol.Headers
import http4stus.protocol.TusConfig
import http4stus.protocol.headers.TusExtension
import http4stus.protocol.headers.TusResumable
import http4stus.protocol.headers.TusVersion
import http4stus.protocol.headers.UploadDeferLength
import http4stus.protocol.headers.UploadLength
import http4stus.protocol.headers.UploadMetadata
import http4stus.protocol.headers.UploadOffset
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.headers.Location
import org.http4s.headers.`Cache-Control`
import org.http4s.implicits.*
import scodec.bits.ByteVector

abstract class TusEndpointSuite(endpoint: Resource[IO, Endpoint[IO]])
    extends CatsEffectSuite:

  val endpointFixture = ResourceSuiteLocalFixture("tus-endpoint", endpoint)
  override def munitFixtures = Seq(endpointFixture)

  val baseUri = uri"/"

  val config: TusConfig = endpoint.use(e => IO.pure(e.config)).unsafeRunSync()

  def makeClient = IO(endpointFixture()).map(_.app).map(Client.fromHttpApp[IO])

  test("not found responses"):
    for
      client <- makeClient
      head = Method.HEAD(baseUri / "file11")
      _ <- client.run(head).use(assertStatus(Status.NotFound))

      patch = Method.PATCH(baseUri / "file11")
      _ <- client.run(head).use(assertStatus(Status.NotFound))
    yield ()

  test("options request"):
    for
      client <- makeClient
      opt = Method.OPTIONS(baseUri)
      _ <- client
        .run(opt)
        .use(resp =>
          IO {
            assertEquals(resp.status, Status.NoContent)
            assertEquals(resp.headers.get[TusVersion], Some(TusVersion(Version.V1_0_0)))
            assertEquals(
              resp.headers.get[TusResumable],
              Some(TusResumable(Version.V1_0_0))
            )
            assert(
              resp.headers.get[TusExtension].exists(_.findCreation.isDefined),
              "No creation extension"
            )
          }
        )
    yield ()

  test("create and upload one pass"):
    for
      client <- makeClient
      meta = MetadataMap.empty.withFilename("test.txt")
      create = Method
        .POST(baseUri)
        .putHeaders(UploadMetadata(meta))
        .putHeaders(UploadDeferLength.value)
      uploadUri <- client.run(create).use { r =>
        assertEquals(r.status, Status.Created)
        IO(r.headers.get[Location].get.uri)
      }
      _ <- checkUpload(client)(
        uploadUri,
        meta,
        ByteSize.bytes(0),
        ByteSize.bytes(11),
        true
      )

      uploadReq = Method
        .PATCH("hello-world".getBytes, uploadUri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset.zero)
        .putHeaders(UploadLength(ByteSize.bytes(11)))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq).use { r =>
        IO {
          assertEquals(r.status, Status.NoContent)
          assertEquals(
            r.headers.get[UploadOffset].map(_.offset),
            Some(ByteSize.bytes(11))
          )
          assert(r.headers.get[TusResumable].isDefined)
        }
      }

      _ <- checkUpload(client)(
        uploadUri,
        meta,
        ByteSize.bytes(11),
        ByteSize.bytes(11),
        false
      )
    yield ()

  test("create and upload two pass"):
    for
      client <- makeClient
      meta = MetadataMap.empty.withFilename("test.txt")
      create = Method
        .POST(baseUri)
        .putHeaders(UploadMetadata(meta))
        .putHeaders(UploadLength(ByteSize.bytes(11)))
      uploadUri <- client.run(create).use { r =>
        assertEquals(r.status, Status.Created)
        IO(r.headers.get[Location].get.uri)
      }

      uploadReq1 = Method
        .PATCH("hello".getBytes, uploadUri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset.zero)
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq1).use { r =>
        IO {
          assertEquals(r.status, Status.NoContent)
          assertEquals(
            r.headers.get[UploadOffset].map(_.offset),
            Some(ByteSize.bytes(5))
          )
          assert(r.headers.get[TusResumable].isDefined)
        }
      }
      _ <- checkUpload(client)(
        uploadUri,
        meta,
        ByteSize.bytes(5),
        ByteSize.bytes(11),
        false
      )

      uploadReq2 = Method
        .PATCH("-world".getBytes, uploadUri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset(ByteSize.bytes(5)))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq2).use { r =>
        IO {
          assertEquals(r.status, Status.NoContent)
          assertEquals(
            r.headers.get[UploadOffset].map(_.offset),
            Some(ByteSize.bytes(11))
          )
          assert(r.headers.get[TusResumable].isDefined)
        }
      }
      _ <- checkUpload(client)(
        uploadUri,
        meta,
        ByteSize.bytes(11),
        ByteSize.bytes(11),
        false
      )
    yield ()

  test("termination"):
    assume(Extension.hasTermination(config.extensions))
    for
      client <- makeClient
      create = Method
        .POST(baseUri)
        .putHeaders(UploadLength(ByteSize.bytes(11)))
      uri <- client.run(create).use { r =>
        assertEquals(r.status, Status.Created)
        IO(r.headers.get[Location].get.uri)
      }
      uploadReq = Method
        .PATCH("hello-world".getBytes, uri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset.zero)
        .putHeaders(UploadLength(ByteSize.bytes(11)))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq).use(assertStatus(Status.NoContent))

      delete = Method.DELETE(uri).putHeaders(TusResumable.V1_0_0)
      _ <- client.run(delete).use(assertStatus(Status.NoContent))
      _ <- client.run(Method.HEAD(uri)).use(assertStatus(Status.NotFound))
    yield ()

  test("max upload size"):
    assume(
      config.maxSize.exists(_ <= ByteSize.bytes(100)),
      "Only max-size < 100k supported"
    )
    for
      client <- makeClient
      // first simply ask to create a too large upload
      c1 = Method.POST(baseUri).putHeaders(UploadLength(ByteSize.mb(1)))
      _ <- client
        .run(c1)
        .attempt
        .map {
          case Right(_) => fail("expected request to fail")
          case Left(err) =>
            assertEquals(
              err,
              TusDecodeFailure.MaxSizeExceeded(config.maxSize.get, ByteSize.mb(1))
            )
        }
        .use_

      // ask for a small upload, but send too much data anyways
      c2 = Method.POST(baseUri).putHeaders(UploadLength(ByteSize.bytes(11)))
      c2Uri <- client.run(c2).use { r =>
        assertEquals(r.status, Status.Created)
        IO(r.headers.get[Location].get.uri)
      }
      data = ByteVector.fill(config.maxSize.get.toBytes + 2)('a'.toByte)
      uploadReq = Method
        .PATCH(data, c2Uri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset.zero)
        .putHeaders(TusResumable.V1_0_0)
      _ <- client
        .run(uploadReq)
        .attempt
        .map {
          case Right(_) => fail("expected request to fail")
          case Left(err) =>
            assertEquals(
              err,
              TusDecodeFailure.MaxSizeExceeded(config.maxSize.get, config.maxSize.get)
            )
        }
        .use_
    yield ()

  def checkUpload(
      client: Client[IO]
  )(uri: Uri, meta: MetadataMap, offset: ByteSize, len: ByteSize, deferLength: Boolean) =
    client
      .run(Method.HEAD(uri))
      .map { r =>
        assertEquals(r.status, Status.NoContent)
        assertEquals(r.headers.get[UploadMetadata], Some(UploadMetadata(meta)))
        assertEquals(
          r.headers.get[`Cache-Control`].map(_.values),
          Some(NonEmptyList.of(CacheDirective.`no-store`))
        )
        assertEquals(
          r.headers.get[UploadOffset].map(_.offset),
          Some(offset)
        )
        assertEquals(
          r.headers.get[UploadLength].map(_.length),
          if (deferLength) None else Some(len)
        )
        assert(r.headers.get[UploadDeferLength].isDefined == deferLength)
      }
      .use_

  def assertStatus(expect: Status)(r: Response[IO]) =
    IO(assertEquals(r.status, expect))
