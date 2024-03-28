package http4stus.protocol.creation.headers

import cats.data.NonEmptyList
import cats.syntax.all.*

import http4stus.data.MetadataMap
import http4stus.data.MetadataMap.Key
import http4stus.internal.StringUtil
import http4stus.protocol.creation.headers.UploadMetadata.base64Enc
import org.http4s.Header
import org.http4s.ParseFailure
import org.http4s.ParseResult
import org.typelevel.ci.CIString
import scodec.bits.ByteVector

final case class UploadMetadata(decoded: MetadataMap):
  def encoded: String =
    decoded.data
      .map { case (k, v) =>
        if (v.isEmpty) k.name
        else s"${k.name} ${base64Enc(v)}"
      }
      .toList
      .mkString(",")

object UploadMetadata:
  val name: CIString = CIString("Upload-Metadata")

  given Header[UploadMetadata, Header.Single] =
    Header.create(name, _.encoded, parse)

  def parse(s: String): Either[ParseFailure, UploadMetadata] =
    StringUtil
      .commaList(s)
      .toRight(ParseFailure(s"No value provided for $name", ""))
      .flatMap(nel => nel.traverse(StringUtil.pair(_, ' ')).leftMap(ParseFailure(_, "")))
      .flatMap(nel => nel.traverse(readKeyValue))
      .map(UploadMetadata.fromNel)

  private def fromNel(nel: NonEmptyList[(Key, ByteVector)]): UploadMetadata =
    UploadMetadata(MetadataMap(nel.toList.toMap))

  private def readKeyValue(kv: (String, String)): ParseResult[(Key, ByteVector)] =
    val (k, v) = kv
    Key
      .fromString(k)
      .flatMap(key => base64Dec(v).map(value => key -> value))
      .leftMap(ParseFailure(s"Cannot decode ($k, $v) pair", _))

  private def base64Enc(v: ByteVector): String = v.toBase64
  private def base64Dec(s: String): Either[String, ByteVector] =
    ByteVector.fromBase64Descriptive(s)
