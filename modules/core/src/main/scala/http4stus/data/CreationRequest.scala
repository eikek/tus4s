package http4stus.data

import cats.Applicative
import cats.data.EitherT
import cats.syntax.all.*
import fs2.Stream

import http4stus.protocol.Headers
import http4stus.protocol.headers.*
import org.http4s.*
import org.http4s.headers.`Content-Length`

final case class CreationRequest[F[_]](
    meta: MetadataMap,
    hasContent: Boolean,
    uploadLength: Option[ByteSize],
    contentLength: Option[ByteSize],
    checksum: Option[UploadChecksum],
    isPartial: Boolean,
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
      val checksum = req.headers.get[UploadChecksum]
      val partial = req.headers.get[UploadConcat].exists(_.isPartial)

      EitherT.fromEither(
        uploadLen.map(upLen =>
          CreationRequest(
            meta,
            true,
            upLen,
            cntLen.map(ByteSize.bytes),
            checksum,
            partial,
            data
          )
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
          CreationRequest(meta, false, ulen, Some(ByteSize.zero), None, false, Stream.empty)
        )
      )
    }

  given [F[_]: Applicative]: EntityDecoder[F, CreationRequest[F]] =
    withUpload.orElse(withoutUpload)
