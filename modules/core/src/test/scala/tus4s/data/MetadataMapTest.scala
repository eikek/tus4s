package tus4s.core.data

import munit.*
import scodec.bits.ByteVector
import tus4s.core.data.MetadataMap.Key

class MetadataMapTest extends FunSuite:
  val key1 = Key.unsafeFromString("filename")
  val key2 = Key.unsafeFromString("is-obsolete")

  test("key not contain invalid characters"):
    assert(Key.fromString("a,b").isLeft)
    assert(Key.fromString("a b").isLeft)
    assert(Key.fromString("").isLeft)
    assert(Key.fromString("üäö").isLeft)

  test("key with ascii only"):
    assertEquals(Key.fromString("filename"), Right(key1))
    assertEquals(Key.fromString("is-obsolete"), Right(key2))

  test("get flag"):
    val m = MetadataMap(key2 -> ByteVector.empty)
    assert(m.exists(key2))
    assert(!m.exists(key1))

  test("get value"):
    val m = MetadataMap(
      key2 -> ByteVector.empty,
      key1 -> ByteVector.view("myname.txt".getBytes())
    )
    assertEquals(m.getString(key1), Some("myname.txt"))

  test("parse and encode map"):
    val raw = "filename cGVyc8O2bmxpY2gub2R0,is-obsolete"
    val m = MetadataMap.parseTus(raw).fold(sys.error, identity)
    assertEquals(m.getString(key1), Some("persönlich.odt"))
    assert(m.exists(key2))
    assertEquals(m.encoded, raw)
