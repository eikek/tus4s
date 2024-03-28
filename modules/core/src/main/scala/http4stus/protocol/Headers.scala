package http4stus.protocol

import org.http4s.headers.`Content-Type`
import org.http4s.*
import org.http4s.implicits.*

object Headers:

  val offsetOctetStream = mediaType"application/offset+octet-stream"
  val contentTypeOffsetOctetStream = `Content-Type`(offsetOctetStream, None)

  def isOffsetOctetStream[F[_]](req: Request[F]): Boolean =
    req.contentType.exists(_ == offsetOctetStream)
