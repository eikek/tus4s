package http4stus.protocol

import http4stus.data.ByteSize
import http4stus.protocol.headers.UploadDeferLength
import http4stus.protocol.headers.UploadLength
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

object Headers:

  val offsetOctetStream: MediaType = mediaType"application/offset+octet-stream"
  val contentTypeOffsetOctetStream = `Content-Type`(offsetOctetStream, None)

  def isOffsetOctetStream[F[_]](req: Request[F]): Boolean =
    req.contentType.exists(_ == offsetOctetStream)

  def uploadLengthOrDeferred[F[_]](
      req: Media[F]
  ): Either[MalformedMessageBodyFailure, Option[ByteSize]] =
    val defer = req.headers.get[UploadDeferLength]
    val len = req.headers.get[UploadLength].map(_.length)
    (defer, len) match
      case (Some(_), Some(_)) =>
        Left(
          MalformedMessageBodyFailure(
            "UploadDeferLength and UploadLength headers are present"
          )
        )
      case (None, None) =>
        Left(
          MalformedMessageBodyFailure("UploadDeferLength or UploadLength headers missing")
        )
      case (Some(_), None) => Right(None)
      case (None, len)     => Right(len)
