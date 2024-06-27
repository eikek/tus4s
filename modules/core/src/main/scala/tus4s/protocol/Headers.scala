package tus4s.protocol

import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

object Headers:
  val checksumMismatch: Status = Status.fromInt(460).fold(throw _, identity)
  val offsetOctetStream: MediaType = mediaType"application/offset+octet-stream"
  val contentTypeOffsetOctetStream = `Content-Type`(offsetOctetStream, None)

  def isOffsetOctetStream[F[_]](req: Request[F]): Boolean =
    req.contentType.exists(_ == offsetOctetStream)
