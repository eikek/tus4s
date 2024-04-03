package http4stus.protocol.headers

import http4stus.data.*
import org.typelevel.ci.CIString
import org.http4s.Header
import org.http4s.ParseResult

final case class UploadConcat(concatType: ConcatType):
  def isPartial: Boolean = concatType match
    case ConcatType.Partial  => true
    case ConcatType.Final(_) => false

  def render: String = concatType match
    case ConcatType.Partial => "partial"
    case ConcatType.Final(uris) =>
      val list = uris.toList.map(_.renderString).mkString(" ")
      s"final; $list"

object UploadConcat:
  val name: CIString = CIString("Upload-Concat")

  given Header[UploadConcat, Header.Single] =
    Header.create(name, _.render, parse)

  def parse(s: String): ParseResult[UploadConcat] =
    ConcatType.fromString(s).map(UploadConcat.apply)
