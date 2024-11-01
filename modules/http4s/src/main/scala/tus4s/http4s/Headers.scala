package tus4s.http4s

import cats.data.NonEmptyList

import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import tus4s.core.internal.StringUtil

object Headers:
  val checksumMismatch: Status = Status.fromInt(460).fold(throw _, identity)
  val offsetOctetStream: MediaType = mediaType"application/offset+octet-stream"
  val contentTypeOffsetOctetStream = `Content-Type`(offsetOctetStream, None)

  def isOffsetOctetStream[F[_]](req: Request[F]): Boolean =
    req.contentType.exists(_ == offsetOctetStream)

  def commaListHeader[A](
      name: CIString,
      value: String,
      single: String => Either[String, A]
  ): Either[ParseFailure, NonEmptyList[A]] =
    StringUtil
      .commaList(value)
      .toRight(s"No value for header $name")
      .flatMap(_.traverse(single))
      .left
      .map(err => ParseFailure(s"Invalid value for header $name: $value", err))
