package http4stus.protocol.creation

import http4stus.data.*
import org.http4s.*
import http4stus.protocol.Headers
import cats.Applicative
import http4stus.protocol.creation.headers.UploadMetadata
import cats.syntax.all.*
import fs2.Stream
import org.http4s.headers.`Content-Length`
import cats.data.EitherT

final case class CreationRequest[F[_]](
    meta: MetadataMap,
    hasContent: Boolean,
    uploadLength: Option[ByteSize],
    contentLength: Option[ByteSize],
    data: Stream[F, Byte]
)

object CreationRequest:

  private def withUpload[F[_]: Applicative]: EntityDecoder[F, CreationRequest[F]] =
    EntityDecoder.decodeBy(Headers.offsetOctetStream) { req =>
      val uploadLen = Headers.uploadLengthOrDeferred(req)
      val cntLen = req.headers
        .get[`Content-Length`]
        .map(_.length)
      val data = cntLen.map(len => req.body.take(len)).getOrElse(req.body)
      val meta =
        req.headers.get[UploadMetadata].map(_.decoded).getOrElse(MetadataMap.empty)

      EitherT.fromEither(
        uploadLen.map(upLen =>
          CreationRequest(meta, true, upLen, cntLen.map(ByteSize.bytes), data)
        )
      )
    }

  private def withoutUpload[F[_]: Applicative]: EntityDecoder[F, CreationRequest[F]] =
    EntityDecoder.decodeBy(MediaRange.`*/*`) { req =>
      val uploadLen = Headers.uploadLengthOrDeferred(req)
      val meta =
        req.headers.get[UploadMetadata].map(_.decoded).getOrElse(MetadataMap.empty)

      EitherT.fromEither(
        uploadLen.map(ulen =>
          CreationRequest(meta, false, ulen, Some(ByteSize.zero), Stream.empty)
        )
      )
    }

  given [F[_]: Applicative]: EntityDecoder[F, CreationRequest[F]] =
    withUpload.orElse(withoutUpload)
