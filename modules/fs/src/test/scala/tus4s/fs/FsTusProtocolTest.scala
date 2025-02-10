package tus4s.fs

import cats.data.NonEmptyList
import cats.effect.*
import fs2.io.file.Files

import munit.*
import scodec.bits.*
import tus4s.core.data.ByteSize
import tus4s.core.data.ConcatRequest
import tus4s.core.data.ConcatResult
import tus4s.core.data.CreationResult
import tus4s.core.data.MetadataMap
import tus4s.core.data.UploadRequest
import tus4s.core.data.Url

class FsTusProtocolTest extends CatsEffectSuite:

  val tus = for {
    path <- Files[IO].tempDirectory
    p <- Resource.eval(
      FsTusProtocol.create[IO](path, Some(ByteSize.kb(2)), true, true, true)
    )
  } yield p

  def b(str: String): ByteVector = ByteVector.view(str.getBytes())

  test("create and load"):
    tus.use { tp =>
      val data = ByteVector.view("hello world".getBytes())
      val req = UploadRequest
        .fromByteVector[IO](data)
        .withMeta(MetadataMap.Key.fileName, b("test.txt"))
      tp.create(req).flatMap {
        case CreationResult.Success(id, offset, expires) =>
          assertEquals(offset, ByteSize.bytes(data.length))
          assertEquals(expires, None)

          tp.find(id).map { file =>
            assert(file.isDefined)
            val f = file.get
            assertEquals(f.getFileName, Some("test.txt"))
            assert(f.hasContent)
          }

        case r =>
          fail(s"Unexpected creation result: $r")
      }
    }

  test("advertised size too large"):
    tus.use { tp =>
      val data = b("hello world")
      val req = UploadRequest
        .fromByteVector[IO](data)
        .withMeta(MetadataMap.Key.fileName, b("test.txt"))
        .copy(contentLength = Some(ByteSize.kb(3)), uploadLength = Some(ByteSize.kb(3)))
      tp.create(req).map {
        case CreationResult.UploadTooLarge(max, cur) =>
          assertEquals(max, ByteSize.kb(2))
          assertEquals(cur, ByteSize.kb(3))
        case r =>
          fail(s"Unexpected upload result: $r")
      }
    }

  test("given size too large"):
    tus.use { tp =>
      val data = ByteVector.fill(3 * 1024)(1)
      val req = UploadRequest.fromByteVector[IO](data)
      tp.create(req).map {
        case CreationResult.UploadTooLarge(max, cur) =>
          assertEquals(max, ByteSize.kb(2))
          assertEquals(cur, ByteSize.kb(3))
        case r =>
          fail(s"Unexpected upload result: $r")
      }
    }

  test("concatenate two files"):
    tus.use { tp =>
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
