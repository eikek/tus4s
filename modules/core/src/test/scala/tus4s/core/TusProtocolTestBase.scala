package tus4s.core

import cats.data.NonEmptyList
import cats.effect.*
import cats.syntax.all.*

import munit.*
import scodec.bits.*
import tus4s.core.data.ByteRange
import tus4s.core.data.ByteSize
import tus4s.core.data.ConcatRequest
import tus4s.core.data.ConcatResult
import tus4s.core.data.CreationResult
import tus4s.core.data.Extension
import tus4s.core.data.FileResult
import tus4s.core.data.MetadataMap
import tus4s.core.data.ReceiveResult
import tus4s.core.data.UploadId
import tus4s.core.data.UploadRequest
import tus4s.core.data.Url

abstract class TusProtocolTestBase extends CatsEffectSuite:

  def tusProtocol(maxSize: Option[ByteSize]): Resource[IO, TusProtocol[IO]]
  private lazy val tusCfg = tusProtocol(None).use(_.config.pure[IO]).unsafeRunSync()

  def b(str: String): ByteVector = ByteVector.view(str.getBytes())

  test("create and load"):
    tusProtocol(None).use { tp =>
      val data = ByteVector.view("hello world".getBytes())
      val req = UploadRequest
        .fromByteVector[IO](data)
        .withMeta(MetadataMap.Key.fileName, b("test.txt"))
      for
        id <- tp.createSuccess(req).map(_.id)

        fr1 <- tp.findFileWithContent(id, ByteRange.all)
        _ <- fr1.dataUtf8.assertEquals("hello world")
        _ = assertEquals(fr1.getFileName, Some("test.txt"))
        _ = assertEquals(fr1.state.length, Some(ByteSize.bytes(11)))

        fr2 <- tp.findFileWithContent(id, ByteRange.bytes(2, 2))
        _ <- fr2.dataUtf8.assertEquals("ll")
        _ = assertEquals(fr2.state.length, Some(ByteSize.bytes(11)))

        fr3 <- tp.findFileWithContent(id, ByteRange.bytes(6, 999))
        _ <- fr3.dataUtf8.assertEquals("world")

        fr4 <- tp.findFileWithContent(id, ByteRange.bytes(999, 1200))
        _ <- fr4.dataUtf8.assertEquals("")
      yield ()
    }

  test("load chunk"):
    assume(tusCfg.rangeRequests, "RangeRequests not enabled")
    tusProtocol(None).use { tp =>
      val data = b("hello world")
      val req = UploadRequest.fromByteVector[IO](data)
      tp.createSuccess(req).flatMap { case CreationResult.Success(id, _, _) =>
        tp.findFileWithContent(id, ByteRange(ByteSize.bytes(2), ByteSize.bytes(5)))
          .flatMap { r =>
            assertEquals(r.state.length, Some(ByteSize.bytes(11)))
            val cnt = r.data.through(fs2.text.utf8.decode).compile.string
            cnt.assertEquals("llo w")
          }
      }
    }

  test("load chunk over concatenated files"):
    assume(
      tusCfg.rangeRequests && tusCfg.extensions.contains(Extension.Concatenation),
      "RangeRequests and Concatenation extension not enabled"
    )
    tusProtocol(None).use { tp =>
      // hello world today.
      val req1 = UploadRequest.fromByteVector[IO](b("hello ")).withPartial(true)
      val req2 = UploadRequest.fromByteVector[IO](b("world ")).withPartial(true)
      val req3 = UploadRequest.fromByteVector[IO](b("today.")).withPartial(true)

      for
        id1 <- tp.createSuccess(req1).map(_.id)
        id2 <- tp.createSuccess(req2).map(_.id)
        id3 <- tp.createSuccess(req3).map(_.id)

        ccr = ConcatRequest(
          NonEmptyList.of(id1, id2, id3),
          NonEmptyList.of(
            Url(s"/files/${id1}"),
            Url(s"/files/${id2}"),
            Url(s"/files/${id3}")
          )
        )
        res <- tp.concat(ccr)
        id = res match
          case ConcatResult.Success(id) =>
            assertNotEquals(id, id1)
            assertNotEquals(id, id2)
            assertNotEquals(id, id3)
            id

          case ConcatResult.PartsNotFound(ids) =>
            fail(s"Missing parts for concat: $ids")

        fr1 <- tp.findFileWithContent(id, ByteRange.bytes(8, 2))
        _ <- fr1.dataUtf8.assertEquals("rl")
        _ = assert(fr1.state.isDone)
        _ = assert(fr1.state.isFinal)
        _ = assertEquals(fr1.state.length, Some(ByteSize.bytes(18)))

        fr2 <- tp.findFileWithContent(id, ByteRange.bytes(8, 8))
        _ <- fr2.dataUtf8.assertEquals("rld toda")
        _ = assertEquals(fr2.state.length, Some(ByteSize.bytes(18)))

        fr3 <- tp.findFileWithContent(id, ByteRange.bytes(2, 12))
        _ <- fr3.dataUtf8.assertEquals("llo world to")
        _ = assertEquals(fr3.state.length, Some(ByteSize.bytes(18)))

        fr4 <- tp.findFileWithContent(id, ByteRange.bytes(0, 99999))
        _ <- fr4.dataUtf8.assertEquals("hello world today.")
        _ = assertEquals(fr4.state.length, Some(ByteSize.bytes(18)))

        fr5 <- tp.findFileWithoutContent(id, ByteRange.none)
        _ <- fr5.dataUtf8.assertEquals("")
        _ = assertEquals(fr5.state.length, Some(ByteSize.bytes(18)))

        fr6 <- tp.findFileWithContent(id, ByteRange.from(ByteSize.bytes(5)))
        _ <- fr6.dataUtf8.assertEquals(" world today.")
        _ = assertEquals(fr6.state.length, Some(ByteSize.bytes(18)))
      yield ()
    }

  test("create: empty upload"):
    tusProtocol(None).use { tp =>
      val req = UploadRequest.fromByteVector[IO](ByteVector.empty)
      tp.createSuccess(req).flatMap { case CreationResult.Success(id, offset, expires) =>
        assertEquals(offset, ByteSize.zero)
        tp.findFileWithoutContent(id, ByteRange.all)
      }
    }

  test("create: advertised size too large"):
    tusProtocol(ByteSize.kb(2).some).use { tp =>
      val data = b("hello world")
      val req = UploadRequest
        .fromByteVector[IO](data)
        .withMeta(MetadataMap.Key.fileName, b("test.txt"))
        .copy(contentLength = ByteSize.kb(3).some, uploadLength = ByteSize.kb(3).some)
      tp.create(req).map {
        case CreationResult.UploadTooLarge(max, cur) =>
          assertEquals(max, ByteSize.kb(2))
          assertEquals(cur, ByteSize.kb(3))
        case r =>
          fail(s"Unexpected upload result: $r")
      }
    }

  test("create: given size too large"):
    tusProtocol(ByteSize.kb(2).some).use { tp =>
      val data = ByteVector.fill(3 * 1024)(1)
      val req = UploadRequest
        .fromByteVector[IO](data)
        .copy(contentLength = ByteSize.kb(1).some, uploadLength = ByteSize.kb(1).some)
      tp.create(req).map {
        case CreationResult.UploadTooLarge(max, cur) =>
          assertEquals(max, ByteSize.kb(2))
        case r =>
          fail(s"Unexpected upload result: $r")
      }
    }

  test("receive and load initial chunk"):
    val data = b("hello world")
    tusProtocol(None).use { tp =>
      tp.createEmpty().flatMap { id =>
        val req = UploadRequest.fromByteVector[IO](data)
        tp.receive(id, req).flatMap {
          case ReceiveResult.Success(size, _) =>
            assertEquals(size, ByteSize.bytes(data.length))
            tp.findFileWithContent(id).map(f => assertEquals(f.getFileName, None))

          case r =>
            fail(s"Unexpected receive result: $r")
        }
      }
    }

  test("receive two chunks"):
    val data = b("hello world")
    tusProtocol(None).use { tp =>
      for
        id <- tp.createEmpty()

        req1 = UploadRequest
          .fromByteVector[IO](data)
          .copy(uploadLength = ByteSize.bytes(data.length * 2).some)
        r1 <- tp.receiveSuccess(id, req1)
        req2 = req1.copy(offset = r1.offset)
        _ <- tp.receiveSuccess(id, req2)
        file <- tp.findFileWithContent(id)
        cnt <- file.data.through(fs2.text.utf8.decode).compile.string
        _ = assertEquals(cnt, "hello world".repeat(2))
      yield ()
    }

  test("no overwrite final upload"):
    val data = b("hello")
    tusProtocol(None).use { tp =>
      for
        id <- tp.createEmpty(_.copy(uploadLength = ByteSize.bytes(data.length).some))
        req = UploadRequest.fromByteVector[IO](data)
        r1 <- tp.receiveSuccess(id, req)
        res <- tp.receive(id, req.copy(offset = r1.offset))
        _ = res match
          case ReceiveResult.UploadDone => ()
          case _                        => fail(s"Unexpected receive result: $res")
      yield ()
    }

  test("offset mismatch"):
    val data = b("hello")
    tusProtocol(None).use { tp =>
      for
        id <- tp.createEmpty()
        req = UploadRequest
          .fromByteVector[IO](data)
          .copy(uploadLength = ByteSize.bytes(data.length + 2).some)
        r1 <- tp.receiveSuccess(id, req)
        res <- tp.receive(id, req.copy(offset = ByteSize.bytes(data.length - 1)))
        _ = res match
          case ReceiveResult.OffsetMismatch(offset) =>
            assertEquals(offset, ByteSize.bytes(data.length))
          case _ => fail(s"Unexpected receive result: $res")
      yield ()
    }

  test("concatenate two files"):
    tusProtocol(None).use { tp =>
      assume(
        tp.config.extensions.contains(Extension.Concatenation),
        "Concatenation extension not enabled"
      )

      val data1 = b("hello ")
      val data2 = b("world")
      val req1 = UploadRequest.fromByteVector[IO](data1).withPartial(true)
      val req2 = UploadRequest.fromByteVector[IO](data2).withPartial(true)

      for
        cr1 <- tp.create(req1)
        cr2 <- tp.create(req2)
        id1 = cr1 match
          case CreationResult.Success(id, _, _) => id
          case _                                => fail("")
        id2 = cr2 match
          case CreationResult.Success(id, _, _) => id
          case _                                => fail("")

        ccr = ConcatRequest(
          NonEmptyList.of(id1, id2),
          NonEmptyList.of(Url(s"/files/${id1}"), Url(s"/files/${id2}"))
        )
        res <- tp.concat(ccr)
        id = res match
          case ConcatResult.Success(id) =>
            assertNotEquals(id, id1)
            assertNotEquals(id, id2)
            id

          case ConcatResult.PartsNotFound(ids) =>
            fail(s"Missing parts for concat: $ids")

        fileResult <- tp.find(id, ByteRange.all).map(_.get)
        content <- fileResult.data
          .through(fs2.text.utf8.decode)
          .compile
          .string
        _ = assertEquals(content, "hello world")
        _ = assert(fileResult.state.isDone)
        _ = assert(fileResult.state.isFinal)
      yield ()
    }

  test("delete upload"):
    tusProtocol(None).use { tus =>
      assume(
        tus.config.extensions.contains(Extension.Termination),
        "Termination extension not enabled"
      )

      val data = ByteVector.view("hello world".getBytes())
      val req = UploadRequest
        .fromByteVector[IO](data)
        .withMeta(MetadataMap.Key.fileName, b("test.txt"))

      for
        result <- tus.create(req)
        id = result match
          case CreationResult.Success(id, _, _) => id
          case _                                => fail(s"Unexpected result: $result")

        _ <- tus.delete(id)
        _ <- tus.find(id, ByteRange.all).assertEquals(None)
      yield ()
    }

  extension (self: TusProtocol[IO])
    def createEmpty(
        freq: UploadRequest[IO] => UploadRequest[IO] = identity
    ): IO[UploadId] =
      val req = freq(UploadRequest.fromByteVector[IO](ByteVector.empty))
      self.create(req).map {
        case CreationResult.Success(id, _, _) => id
        case r                                => fail(s"Unexpected creation result: $r")
      }

    def findFileWithContent(
        id: UploadId,
        range: ByteRange = ByteRange.all
    ): IO[FileResult[IO]] =
      self.find(id, range).map { file =>
        assert(file.isDefined)
        val f = file.get
        assert(f.hasContent)
        f
      }

    def findFileWithoutContent(id: UploadId, range: ByteRange): IO[FileResult[IO]] =
      self.find(id, range).map { file =>
        assert(file.isDefined)
        val f = file.get
        assert(!f.hasContent)
        f
      }

    def receiveSuccess(id: UploadId, req: UploadRequest[IO]): IO[ReceiveResult.Success] =
      self.receive(id, req).map {
        case r: ReceiveResult.Success => r
        case r                        => fail(s"Unexpected receive result: $r")
      }

    def createSuccess(req: UploadRequest[IO]): IO[CreationResult.Success] =
      self.create(req).map {
        case r: CreationResult.Success => r
        case r                         => fail(s"Unexpected creation result: $r")
      }

  extension (self: FileResult[IO])
    def dataUtf8: IO[String] = self.data.through(fs2.text.utf8.decode).compile.string

    def checkText(expected: String) =
      self.dataUtf8.assertEquals(expected)
