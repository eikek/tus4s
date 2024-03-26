package http4stus.protocol.creation.headers

import cats.syntax.all.*
import UploadMetadata.{Key, base64Enc}
import java.nio.charset.Charset
import java.util.Base64
import org.http4s.Header
import org.typelevel.ci.CIString
import org.http4s.ParseFailure
import http4stus.internal.StringUtil
import cats.data.NonEmptyList
import org.http4s.ParseResult

final case class UploadMetadata(decoded: NonEmptyList[(Key, String)]):
  def withValue(key: Key, value: String): UploadMetadata =
    copy(decoded = NonEmptyList(key -> value, decoded.filterNot(_._1 == key)))

  def get(key: Key): Option[String] =
    decoded.find(_._1 == key).map(_._2)

  def exists(key: Key): Boolean =
    decoded.exists(_._1 == key)

  def encoded: String =
    decoded
      .map { case (k, v) =>
        if (v.isEmpty()) k.name
        else s"${k.name} ${base64Enc(v)}"
      }
      .toList
      .mkString(",")

object UploadMetadata:
  val name: CIString = CIString("Upload-Metadata")

  opaque type Key = String
  object Key:
    private val ascii = Charset.forName("ASCII").newEncoder()
    private val invalidChar: Char => Boolean = c => c.isWhitespace || c == ','

    def fromString(key: String): Either[String, Key] =
      if (key.isEmpty || key.exists(invalidChar))
        Left(s"Invalid $name key name (no spaces, commas, not empty): $key")
      else if (ascii.canEncode(key)) Right(key)
      else Left(s"Key names for $name must be ascii only: $key")

    def unsafeFromString(name: String): Key =
      fromString(name).fold(sys.error, identity)

    extension (self: Key) def name: String = self

  given Header[UploadMetadata, Header.Single] =
    Header.create(name, _.encoded, parse)

  def parse(s: String): Either[ParseFailure, UploadMetadata] =
    StringUtil
      .commaList(s)
      .toRight(ParseFailure(s"No value provided for $name", ""))
      .flatMap(nel => nel.traverse(StringUtil.pair(_, ' ')).leftMap(ParseFailure(_, "")))
      .flatMap(nel => nel.traverse(readKeyValue))
      .map(UploadMetadata.apply)

  private def readKeyValue(kv: (String, String)): ParseResult[(Key, String)] =
    val (k, v) = kv
    Key
      .fromString(k)
      .flatMap(key => base64Dec(v).map(value => key -> value))
      .leftMap(ParseFailure(s"Cannot decode ($k, $v) pair", _))

  private def base64Enc(s: String): String =
    new String(Base64.getEncoder().encode(s.getBytes("UTF-8")), "ASCII")

  private def base64Dec(s: String): Either[String, String] =
    try Right(new String(Base64.getDecoder().decode(s), "UTF-8"))
    catch case e: Exception => Left(s"Cannot base64 decode '$s': ${e.getMessage}")
