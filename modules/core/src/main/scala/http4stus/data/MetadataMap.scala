package http4stus.data

import java.nio.charset.Charset

import cats.kernel.Monoid

import http4stus.data.MetadataMap.Key
import scodec.bits.ByteVector

final case class MetadataMap(data: Map[Key, ByteVector]):
  def withValue(key: Key, value: ByteVector): MetadataMap =
    copy(data = data.updated(key, value))

  def get(key: Key): Option[ByteVector] = data.get(key)

  def getString(key: Key): Option[String] = get(key).flatMap(_.decodeUtf8.toOption)

  def remove(key: Key): MetadataMap = MetadataMap(data.removed(key))

  def exists(key: Key): Boolean = data.exists(_._1 == key)

  def isEmpty: Boolean = data.isEmpty

  def ++(other: MetadataMap): MetadataMap = MetadataMap(data ++ other.data)

object MetadataMap:
  val empty: MetadataMap = MetadataMap(Map.empty)

  def apply(items: (Key, ByteVector)*): MetadataMap =
    MetadataMap(items.toMap)

  given Monoid[MetadataMap] = Monoid.instance(empty, _ ++ _)

  opaque type Key = String
  object Key:
    // newEncoder is stateful
    private def ascii = Charset.forName("ASCII").newEncoder()
    private val invalidChar: Char => Boolean = c => c.isWhitespace || c == ','

    def fromString(key: String): Either[String, Key] =
      if (key.isEmpty || key.exists(invalidChar))
        Left(s"Invalid key name (no spaces, commas, not empty): $key")
      else if (ascii.canEncode(key)) Right(key)
      else Left(s"Key names for $name must be ascii only: $key")

    def unsafeFromString(name: String): Key =
      fromString(name).fold(sys.error, identity)

    extension (self: Key) def name: String = self
