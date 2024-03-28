package http4stus.server

import org.http4s.*
import org.http4s.headers.`Content-Length`
import http4stus.data.*
import http4stus.protocol.Headers
import http4stus.protocol.headers.*
import cats.Applicative
import cats.syntax.all.*

type IdToUploadChunk[F[_]] = UploadId => UploadChunk[F]

object IdToUploadChunk:
  given [F[_]: Applicative]: EntityDecoder[F, IdToUploadChunk[F]] =
    EntityDecoder.decodeBy(Headers.offsetOctetStream) { req =>
      val offset = req.headers
        .get[UploadOffset]
        .toRight(MalformedMessageBodyFailure(s"No ${UploadOffset.name} header"))
      val uploadLength = req.headers.get[UploadLength].map(_.length)
      val cntLen = req.headers
        .get[`Content-Length`]
        .map(_.length)
      val data = cntLen.map(len => req.body.take(len)).getOrElse(req.body)

      DecodeResult(
        offset
          .map(off =>
            (id: UploadId) =>
              UploadChunk(id, off.offset, cntLen.map(ByteSize.bytes), uploadLength, data)
          )
          .pure[F]
      )
    }
