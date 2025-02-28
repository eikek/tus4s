package tus4s.http4s.server

import cats.Id

import munit.FunSuite
import org.http4s.EntityTag
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.headers.*
import tus4s.core.TusFinder
import tus4s.core.data.ByteSize
import tus4s.core.data.FileResult
import tus4s.core.data.MetadataMap
import tus4s.core.data.UploadId
import tus4s.http4s.headers.*

class RetrieveTest extends FunSuite:

  val id = UploadId.unsafeFromString("abcde")
  val simpleGet = Retrieve.simpleGet[Id]
  val rangeGet = Retrieve.rangeGet[Id]
  val get = Request[Id](method = Method.GET)
  val size = ByteSize.bytes(100)

  def finder(result: FileResult[Id]): TusFinder[Id] =
    TusFinder.apply[Id]((_, _) => Some(result))

  test("simple get: not found when not done"):
    val res = FileResult.empty[Id](id)
    val response = simpleGet.find(finder(res), get, id)
    assertEquals(response.status, Status.NotFound)
    assert(response.headers.get[UploadMetadata].isEmpty)

  test("simple get: ok when file done"):
    val res = FileResult
      .empty[Id](id)
      .modifyState(_.withOffset(size).withLength(size))
      .modifyMeta(_.withFilename("hello.txt"))
    val response = simpleGet.find(finder(res), get, id)
    assertEquals(response.status, Status.Ok)
    assertEquals(response.headers.get[UploadLength], Some(UploadLength(size)))
    assertEquals(
      response.headers.get[UploadMetadata],
      Some(UploadMetadata(MetadataMap.empty.withFilename("hello.txt")))
    )
    assertEquals(
      response.headers.get[`Content-Disposition`].flatMap(_.filename),
      Some("hello.txt")
    )

  test("simple get: if-none-match unmodified"):
    val res = FileResult
      .empty[Id](id)
      .modifyState(_.withOffset(size).withLength(size))
      .modifyMeta(_.withString(MetadataMap.Key.checksum.head, "abcde"))

    val response = simpleGet.find(
      finder(res),
      get.putHeaders(`If-None-Match`(EntityTag("abcde"))),
      id
    )
    assertEquals(response.status, Status.NotModified)
    assertEquals(response.headers.get[UploadLength], Some(UploadLength(size)))
    assertEquals(response.headers.get[ETag].map(_.tag), Some(EntityTag("abcde")))
    assertEquals(
      response.headers.get[UploadMetadata].map(_.decoded),
      Some(MetadataMap.empty.withString(MetadataMap.Key.checksum.head, "abcde"))
    )

  test("simple get: no metadata header"):
    val res = FileResult.empty[Id](id).modifyState(_.withOffset(size).withLength(size))
    val response = simpleGet.find(finder(res), get, id)
    assertEquals(response.status, Status.Ok)
    assert(response.headers.get(UploadMetadata.name).isEmpty)

  test("range get: start only"):
    val res = FileResult
      .empty[Id](id)
      .modifyState(_.withOffset(size).withLength(size))

    val response = rangeGet.find(finder(res), get.putHeaders(Range(20)), id)
    assertEquals(response.status, Status.PartialContent)
    assertEquals(response.headers.get[UploadLength], Some(UploadLength(size)))
    assertEquals(response.contentLength, Some(80L))
    assertEquals(
      response.headers.get[`Content-Range`],
      Some(`Content-Range`(Range.SubRange(20, size.toBytes - 1), Some(size.toBytes)))
    )
    assert(response.headers.get(UploadMetadata.name).isEmpty)

  test("range get: start and end"):
    val res = FileResult
      .empty[Id](id)
      .modifyState(_.withOffset(size).withLength(size))

    val response = rangeGet.find(finder(res), get.putHeaders(Range(20, 49)), id)
    assertEquals(response.status, Status.PartialContent)
    assertEquals(response.headers.get[UploadLength], Some(UploadLength(size)))
    assertEquals(response.contentLength, Some(30L))
    assertEquals(
      response.headers.get[`Content-Range`],
      Some(`Content-Range`(Range.SubRange(20, 49), Some(size.toBytes)))
    )
    assert(response.headers.get(UploadMetadata.name).isEmpty)

  test("range get: invalid range 1"):
    val res = FileResult
      .empty[Id](id)
      .modifyState(_.withOffset(size).withLength(size))

    val response = rangeGet.find(finder(res), get.putHeaders(Range(49, 20)), id)
    assertEquals(response.status, Status.RangeNotSatisfiable)

  test("range get: invalid range 2"):
    val res = FileResult
      .empty[Id](id)
      .modifyState(_.withOffset(size).withLength(size))

    val response = rangeGet.find(
      finder(res),
      get.putHeaders(Range(size.toBytes + 10, size.toBytes + 20)),
      id
    )
    assertEquals(response.status, Status.RangeNotSatisfiable)
