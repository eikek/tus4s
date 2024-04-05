package http4stus.protocol.headers

import http4stus.data.MetadataMap
import munit.*

class UploadMetadataTest extends FunSuite:

  val key1 = MetadataMap.Key.unsafeFromString("filename")
  val key2 = MetadataMap.Key.unsafeFromString("is-obsolete")

  test("parse and encode map"):
    val raw = "filename cGVyc8O2bmxpY2gub2R0,is-obsolete"
    val m = UploadMetadata.parse(raw).fold(throw _, identity)
    assertEquals(m.decoded.getString(key1), Some("persönlich.odt"))
    assert(m.decoded.exists(key2))
    assertEquals(m.encoded, raw)
