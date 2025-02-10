package tus4s.data

import java.util.UUID

import munit.FunSuite
import tus4s.core.data.UploadId
import wvlet.airframe.ulid.ULID

class UploadIdTest extends FunSuite:

  test("ulid is valid"):
    val ulid = ULID.newULID
    val id = UploadId.fromULID(ulid)
    assertEquals(id.toString(), ulid.toString)

  test("uuid is valid"):
    val uuid = UUID.randomUUID()
    val id = UploadId.fromUUID(uuid)
    assertEquals(id.toString(), uuid.toString())

  test("invalid characters"):
    assert(UploadId.fromString("a/b").isLeft)
    assert(UploadId.fromString("a,b").isLeft)
    assert(UploadId.fromString("a b").isLeft)
    assert(UploadId.fromString("aäöäb").isLeft)
