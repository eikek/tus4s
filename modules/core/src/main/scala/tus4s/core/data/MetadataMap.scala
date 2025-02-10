package tus4s.core.data

import java.nio.charset.Charset

import cats.data.NonEmptyList
import cats.kernel.Monoid
import cats.syntax.all.*

import scodec.bits.ByteVector
import tus4s.core.data.MetadataMap.Key
import tus4s.core.internal.StringUtil

final case class MetadataMap(data: Map[Key, ByteVector]):
  def withValue(key: Key, value: ByteVector): MetadataMap =
    copy(data = data.updated(key, value))

  def withFilename(fn: String): MetadataMap =
    withValue(MetadataMap.Key.fileName.head, ByteVector.view(fn.getBytes))

  def get(key: Key, alternateKeys: Key*): Option[ByteVector] =
    get(NonEmptyList(key, alternateKeys.toList))

  def get(keys: NonEmptyList[Key]): Option[ByteVector] =
    keys.collectFirst(Function.unlift(data.get))

  def getString(keys: NonEmptyList[Key]): Option[String] =
    get(keys).flatMap(_.decodeUtf8.toOption)

  def getString(key: Key, alternateKeys: Key*): Option[String] =
    getString(NonEmptyList(key, alternateKeys.toList))

  def remove(key: Key): MetadataMap = MetadataMap(data.removed(key))

  def exists(key: Key): Boolean = data.exists(_._1 == key)

  def isEmpty: Boolean = data.isEmpty
  def nonEmpty: Boolean = !isEmpty

  def encoded: String =
    data
      .map { case (k, v) =>
        if (v.isEmpty) k.name
        else s"${k.name} ${v.toBase64}"
      }
      .toList
      .mkString(",")

  def ++(other: MetadataMap): MetadataMap = MetadataMap(data ++ other.data)

object MetadataMap:
  val empty: MetadataMap = MetadataMap(Map.empty)

  def apply(items: (Key, ByteVector)*): MetadataMap =
    MetadataMap(items.toMap)

  given Monoid[MetadataMap] = Monoid.instance(empty, _ ++ _)

  /** Parsing tus header string */
  def parseTus(s: String): Either[String, MetadataMap] =
    if (s.isBlank()) Right(MetadataMap.empty)
    else
      StringUtil
        .commaList(s)
        .toRight(s"No value provided for in upload metadata")
        .flatMap(nel => nel.traverse(StringUtil.pair(_, ' ')))
        .flatMap(nel => nel.traverse(readKeyValue))
        .map(fromNel)

  private def fromNel(nel: NonEmptyList[(Key, ByteVector)]): MetadataMap =
    MetadataMap(nel.toList.toMap)

  private def readKeyValue(kv: (String, String)): Either[String, (Key, ByteVector)] =
    val (k, v) = kv
    Key
      .fromString(k)
      .flatMap(key => base64Dec(v).map(value => key -> value))
      .leftMap(err => s"Cannot decode ($k, $v) pair: $err")

  private def base64Dec(s: String): Either[String, ByteVector] =
    if (s.isEmpty()) Right(ByteVector.empty)
    else ByteVector.fromBase64Descriptive(s)

  opaque type Key = String
  object Key:
    // newEncoder is stateful
    private def ascii = Charset.forName("ASCII").newEncoder()
    private val invalidChar: Char => Boolean = c => c.isWhitespace || c == ','

    val contentType: NonEmptyList[Key] =
      NonEmptyList
        .of("contentType", "contenttype", "filetype", "fileType")
        .map(unsafeFromString)

    val fileName: NonEmptyList[Key] =
      NonEmptyList.of("filename", "fileName").map(unsafeFromString)

    val checksum: NonEmptyList[Key] =
      NonEmptyList.of("checksum", "sha1", "sha256", "md5").map(unsafeFromString)

    def fromString(key: String): Either[String, Key] =
      if (key.isEmpty || key.exists(invalidChar))
        Left(s"Invalid key name (no spaces, commas, not empty): $key")
      else if (ascii.canEncode(key)) Right(key)
      else Left(s"Key names for $name must be ascii only: $key")

    def unsafeFromString(name: String): Key =
      fromString(name).fold(sys.error, identity)

    extension (self: Key) def name: String = self
