package http4stus.server

import cats.Monad
import cats.syntax.all.*

import http4stus.data.ByteSize
import http4stus.protocol.headers.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import http4stus.data.Extension
import cats.data.NonEmptyList
import cats.Applicative
import http4stus.data.MetadataMap
import java.time.Instant

private trait Http4sTusDsl[F[_]] extends Http4sDsl[F]:

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
