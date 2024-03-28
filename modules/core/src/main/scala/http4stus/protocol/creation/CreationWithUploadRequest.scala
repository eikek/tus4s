package http4stus.protocol.creation

import cats.Applicative
import cats.data.EitherT
import cats.syntax.all.*
import fs2.Stream

import http4stus.data.*
import http4stus.protocol.Headers
import http4stus.protocol.creation.headers.UploadMetadata
import org.http4s.*
import org.http4s.headers.`Content-Length`

final case class CreationWithUploadRequest[F[_]](
    meta: MetadataMap,
    length: Option[ByteSize],
    data: Stream[F, Byte]
)

object CreationWithUploadRequest:

  given [F[_]: Applicative]: EntityDecoder[F, CreationWithUploadRequest[F]] =
    EntityDecoder.decodeBy(Headers.offsetOctetStream) { req =>
      val uploadLen = Headers.uploadLengthOrDeferred(req)
      val cntLen = req.headers
        .get[`Content-Length`]
        .toRight(MalformedMessageBodyFailure("no Content-Length header found"))
        .map(_.length)
      val meta =
        req.headers.get[UploadMetadata].map(_.decoded).getOrElse(MetadataMap.empty)

      EitherT.fromEither(for {
        upLen <- uploadLen
        cnt <- cntLen
      } yield CreationWithUploadRequest(meta, upLen, req.body.take(cnt)))
    }
