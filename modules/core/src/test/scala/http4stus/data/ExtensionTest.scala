package http4stus.data

import munit.*
import cats.data.NonEmptyList as Nel
import http4stus.data.Extension.includesAlgorithm

class ExtensionTest extends FunSuite:

  List(
    Extension.Expiration -> Nel.of("expiration"),
    Extension.Concatenation -> Nel.of("concatenation"),
    Extension.Termination -> Nel.of("termination"),
    Extension.Checksum(Nel.of(ChecksumAlgorithm.Sha1)) -> Nel.of("checksum"),
    Extension.Creation(Set.empty) -> Nel.of("creation"),
    Extension.Creation(Set(CreationOptions.WithDeferredLength)) -> Nel.of(
      "creation",
      "creation-defer-length"
    ),
    Extension.Creation(Set(CreationOptions.WithUpload)) -> Nel.of(
      "creation",
      "creation-with-upload"
    ),
    Extension.Creation(
      Set(CreationOptions.WithUpload, CreationOptions.WithDeferredLength)
    ) -> Nel.of("creation", "creation-with-upload", "creation-defer-length")
  ).foreach { case (e, names) =>
    test(s"name and fromString: $names") {
      assertEquals(e.names, names)
      assertEquals(Extension.fromStrings(names), Right(Nel.of(e)))
    }
  }

  test("fail fromStrings if remaining"):
    assert(Extension.fromStrings(Nel.of("expiration", "garbage")).isLeft)

  test("fail when creation options, but no creation"):
    assert(Extension.fromStrings(Nel.of("creation-with-upload")).isLeft)

  test("fail with wrong names"):
    assert(Extension.fromStrings(Nel.of("hello")).isLeft)
    assert(Extension.fromStrings(Nel.of("termination", "hello")).isLeft)
    assert(Extension.fromStrings(Nel.of("creation", "hello")).isLeft)

  test("find creation: success"):
    val creation: Extension.Creation = Extension.Creation(Set.empty)
    val exts = Set(Extension.Termination, creation)
    assertEquals(Extension.findCreation(exts), Some(creation))

  test("find creation: none"):
    val exts = Set(Extension.Termination)
    assertEquals(Extension.findCreation(exts), None)
    assertEquals(Extension.findCreation(Set.empty), None)

  test("find checksum: success"):
    val checksum: Extension.Checksum = Extension.Checksum(Nel.of(ChecksumAlgorithm.Sha1))
    val exts = Set(Extension.Expiration, checksum, Extension.Concatenation)
    assertEquals(Extension.findChecksum(exts), Some(checksum))

  test("find checksum: none"):
    val exts = Set(Extension.Expiration, Extension.Concatenation)
    assertEquals(Extension.findChecksum(exts), None)
    assertEquals(Extension.findChecksum(Set.empty), None)

  test("includesAlgorithm"):
    val checksum: Extension.Checksum = Extension.Checksum(Nel.of(ChecksumAlgorithm.Sha1))
    val exts = Set(Extension.Expiration, checksum, Extension.Concatenation)
    assert(Extension.includesAlgorithm(exts, ChecksumAlgorithm.Sha1))
    assert(!Extension.includesAlgorithm(exts, ChecksumAlgorithm.Sha256))

  test("hasConcat"):
    val exts = Set(Extension.Expiration, Extension.Concatenation)
    assert(Extension.hasConcat(exts))
