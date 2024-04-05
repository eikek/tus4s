package http4stus.protocol.headers

import java.time.Instant
import java.time.format.DateTimeFormatter

import cats.syntax.all.*

import org.http4s.Header
import org.http4s.ParseFailure
import org.http4s.ParseResult
import org.typelevel.ci.CIString

final case class UploadExpires(time: Instant):
  def render: String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(time)

object UploadExpires:
  val name: CIString = CIString("Upload-Expires")

  given Header[UploadExpires, Header.Single] =
    Header.create(name, _.render, parse)

  def parse(s: String): ParseResult[UploadExpires] =
    Either
      .catchNonFatal(DateTimeFormatter.RFC_1123_DATE_TIME.parse(s, Instant.from))
      .leftMap(ex => ParseFailure(s"Invalid date time: $s", ex.getMessage()))
      .map(UploadExpires.apply)
