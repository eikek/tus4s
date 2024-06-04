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
import http4stus.protocol.headers.UploadChecksum
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
import http4stus.protocol.headers.UploadConcat

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
      uploadUri <- createUpload(client, ByteSize.bytes(11), meta)

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

  test("create-with-upload"):
    assume(
      Extension
        .findCreation(config.extensions)
        .exists(_.options.contains(CreationOptions.WithUpload))
    )
    for
      client <- makeClient
      create = Method
        .POST("hello world".getBytes(), baseUri)
        .putHeaders(UploadLength(ByteSize.bytes(11)))
        .putHeaders(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset.zero)
        .putHeaders(TusResumable.V1_0_0)
      uri <- client.run(create).use { r =>
        assertEquals(r.status, Status.Created)
        IO(r.headers.get[Location].get.uri)
      }
      _ <- checkUpload(client)(
        uri,
        MetadataMap.empty,
        ByteSize.bytes(11),
        ByteSize.bytes(11),
        false
      )
    yield ()

  test("termination"):
    assume(Extension.hasTermination(config.extensions))
    for
      client <- makeClient
      uri <- createUpload(client, ByteSize.bytes(11))
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

  test("checksum match"):
    val ext = Extension.findChecksum(config.extensions)
    assume(ext.isDefined)
    val alg = ext.get.algorithms.head
    for
      client <- makeClient
      uri <- createUpload(client, ByteSize.bytes(11))
      cs = ByteVector.view("hello-world".getBytes).digest(alg.name)
      uploadReq = Method
        .PATCH("hello-world".getBytes, uri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset.zero)
        .putHeaders(UploadLength(ByteSize.bytes(11)))
        .putHeaders(UploadChecksum(alg, cs))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq).use(assertStatus(Status.NoContent))
    yield ()

  test("checksum mismatch"):
    val ext = Extension.findChecksum(config.extensions)
    assume(ext.isDefined)
    val alg = ext.get.algorithms.head
    for
      client <- makeClient
      uri <- createUpload(client, ByteSize.bytes(11))
      uploadReq = Method
        .PATCH("hello-world".getBytes, uri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset.zero)
        .putHeaders(UploadLength(ByteSize.bytes(11)))
        .putHeaders(UploadChecksum(alg, ByteVector.fromValidHex("caffee")))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq).use(assertStatus(Headers.checksumMismatch))
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

  test("offset mismatch"):
    for
      client <- makeClient
      uri <- createUpload(client, ByteSize.bytes(11))
      uploadReq = Method
        .PATCH("hello".getBytes(), uri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset(ByteSize.bytes(4)))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq).use(assertStatus(Status.Conflict))
    yield ()

  test("upload length mismatch"):
    for
      client <- makeClient
      uri <- createUpload(client, ByteSize.bytes(11))
      uploadReq = Method
        .PATCH("hello".getBytes(), uri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset(ByteSize.zero))
        .putHeaders(UploadLength(ByteSize.bytes(20)))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq).use(assertStatus(Status.Conflict))
    yield ()

  test("upload done"):
    for
      client <- makeClient
      uri <- createUpload(client, ByteSize.bytes(11))
      uploadReq = Method
        .PATCH("hello-world".getBytes(), uri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset(ByteSize.zero))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq).use(assertStatus(Status.NoContent))

      uploadReq2 = Method
        .PATCH("hello-world".getBytes(), uri)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset(ByteSize.bytes(11)))
        .putHeaders(TusResumable.V1_0_0)
      _ <- client.run(uploadReq2).use(assertStatus(Status.Conflict))
    yield ()

  test("concat extension"):
    assume(Extension.hasConcat(config.extensions))
    for
      client <- makeClient
      uri1 <- createUpload(client, ByteSize.bytes(6))
      uri2 <- createUpload(client, ByteSize.bytes(5))

      uploadReq = Method
        .PATCH("hello-".getBytes(), uri1)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset(ByteSize.zero))
        .putHeaders(TusResumable.V1_0_0)
        // miss partial
      _ <- client.run(uploadReq).use(assertStatus(Status.NoContent))

      uploadReq2 = Method
        .PATCH("world".getBytes(), uri2)
        .withContentType(Headers.contentTypeOffsetOctetStream)
        .putHeaders(UploadOffset(ByteSize.zero))
        .putHeaders(TusResumable.V1_0_0)
        //miss partial
      _ <- client.run(uploadReq2).use(assertStatus(Status.NoContent))

      concat = Method.POST(baseUri)
        .putHeaders(UploadConcat(ConcatType.Final(NonEmptyList.of(uri1, uri2))))
      _ <- client.run(concat).use(IO.println)
    yield ()

  def createUpload(
      client: Client[IO],
      size: ByteSize,
      meta: MetadataMap = MetadataMap.empty
  ) =
    val req = Method
      .POST(baseUri)
      .putHeaders(UploadLength(size))
      .putHeaders(TusResumable.V1_0_0)
    val create =
      if (meta.isEmpty) req
      else req.putHeaders(UploadMetadata(meta))
    for uri <- client.run(create).use { r =>
        assertEquals(r.status, Status.Created)
        IO(r.headers.get[Location].get.uri)
      }
    yield uri

  def checkUpload(
      client: Client[IO]
  )(uri: Uri, meta: MetadataMap, offset: ByteSize, len: ByteSize, deferLength: Boolean) =
    client
      .run(Method.HEAD(uri))
      .map { r =>
        assertEquals(r.status, Status.NoContent)
        assertEquals(
          r.headers.get[UploadMetadata],
          if (meta.isEmpty) None else Some(UploadMetadata(meta))
        )
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
