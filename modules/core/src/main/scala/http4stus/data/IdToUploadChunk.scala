package http4stus.data

import cats.Applicative
import cats.syntax.all.*

import http4stus.data.*
import http4stus.protocol.Headers
import http4stus.protocol.headers.*
import org.http4s.headers.`Content-Length`
import org.http4s.{Headers => _, *}

type IdToPatchRequest[F[_]] = UploadId => PatchRequest[F]

object IdToPatchRequest:
  given [F[_]: Applicative]: EntityDecoder[F, IdToPatchRequest[F]] =
    EntityDecoder.decodeBy(Headers.offsetOctetStream) { req =>
      val offset = req.headers
        .get[UploadOffset]
        .toRight(MalformedMessageBodyFailure(s"No ${UploadOffset.name} header"))
      val uploadLength = req.headers.get[UploadLength].map(_.length)
      val cntLen = req.headers
        .get[`Content-Length`]
        .map(_.length)
      val checksum = req.headers.get[UploadChecksum]
      val partial = req.headers.get[UploadConcat].exists(_.isPartial)
      val data = cntLen.map(len => req.body.take(len)).getOrElse(req.body)

      DecodeResult(
        offset
          .map(off =>
            (id: UploadId) =>
              PatchRequest(
                id,
                off.offset,
                cntLen.map(ByteSize.bytes),
                uploadLength,
                checksum,
                partial,
                data
              )
          )
          .pure[F]
      )
    }
