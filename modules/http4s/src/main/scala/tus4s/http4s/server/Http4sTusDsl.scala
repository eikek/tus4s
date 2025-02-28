package tus4s.http4s.server

import java.time.Instant

import cats.Applicative
import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all.*

import org.http4s.*
import org.http4s.dsl.Http4sDsl
import tus4s.core.data.*
import tus4s.http4s.Headers
import tus4s.http4s.headers.*

private[server] trait Tus4sDsl[F[_]] extends Http4sDsl[F]:
  val checksumMismatch: Response[F] =
    Response(Headers.checksumMismatch).withEntity("Checksum mismatch")

  given Uri.Path.SegmentEncoder[UploadId] =
    Uri.Path.SegmentEncoder.instance(a => Uri.Path.Segment(a.value))

  def requireContentType(req: Request[F], mt: MediaType)(
      body: => F[Response[F]]
  )(using F: Applicative[F]): F[Response[F]] =
    if (req.contentType.exists(ct => mt.satisfies(ct.mediaType))) body
    else UnsupportedMediaType(s"Content type $mt is required")

  extension [F[_]: Monad](self: F[Response[F]])
    def putHeader(h: Option[Header.ToRaw]): F[Response[F]] =
      self.map(r => h.map(r.putHeaders(_)).getOrElse(r))

    def withUploadLength(size: Option[ByteSize]): F[Response[F]] =
      size
        .map(s => self.map(_.putHeaders(UploadLength(s))))
        .getOrElse(self.map(_.putHeaders(UploadDeferLength.value)))

    def withOffset(offset: ByteSize): F[Response[F]] =
      self.map(_.putHeaders(UploadOffset(offset)))

    def withMaxSize(maxs: Option[ByteSize]): F[Response[F]] =
      putHeader(maxs.map(TusMaxSize.apply))

    def withExtensions(exts: Set[Extension]): F[Response[F]] =
      putHeader(NonEmptyList.fromList(exts.toList).map(TusExtension.apply))
        .putHeader(
          Extension.findChecksum(exts).map(cs => TusChecksumAlgorithm(cs.algorithms))
        )

    def withExpires(time: Option[Instant]): F[Response[F]] =
      putHeader(time.map(UploadExpires.apply))

    def withTusResumable: F[Response[F]] =
      self.map(_.putHeaders(TusResumable.V1_0_0))

    def withMetadata(meta: MetadataMap): F[Response[F]] =
      if (meta.isEmpty) self
      else self.map(_.putHeaders(UploadMetadata(meta)))

    def withConcatType(ct: Option[ConcatType]): F[Response[F]] =
      putHeader(ct.map(UploadConcat(_)))
