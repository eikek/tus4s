package tus4s.core

import cats.data.NonEmptyList
import cats.effect.*
import cats.syntax.all.*

import munit.*
import scodec.bits.*
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

  def b(str: String): ByteVector = ByteVector.view(str.getBytes())

  test("create and load"):
    tusProtocol(None).use { tp =>
      val data = ByteVector.view("hello world".getBytes())
      val req = UploadRequest
        .fromByteVector[IO](data)
        .withMeta(MetadataMap.Key.fileName, b("test.txt"))
      tp.createSuccess(req).flatMap { case CreationResult.Success(id, offset, expires) =>
        assertEquals(offset, ByteSize.bytes(data.length))
        assertEquals(expires, None)

        tp.findFileWithContent(id)
          .map(f => assertEquals(f.getFileName, Some("test.txt")))
      }
    }

  test("create: empty upload"):
    tusProtocol(None).use { tp =>
      val req = UploadRequest.fromByteVector[IO](ByteVector.empty)
      tp.createSuccess(req).flatMap { case CreationResult.Success(id, offset, expires) =>
        assertEquals(offset, ByteSize.zero)
        tp.findFileWithoutContent(id)
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

        fileResult <- tp.find(id).map(_.get)
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
        _ <- tus.find(id).assertEquals(None)
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

    def findFileWithContent(id: UploadId): IO[FileResult[IO]] =
      self.find(id).map { file =>
        assert(file.isDefined)
        val f = file.get
        assert(f.hasContent)
        f
      }

    def findFileWithoutContent(id: UploadId): IO[FileResult[IO]] =
      self.find(id).map { file =>
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
