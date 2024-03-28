package http4stus.server

import cats.Monad
import cats.syntax.all.*

import http4stus.data.ByteSize
import http4stus.protocol.headers.*
import org.http4s.Header
import org.http4s.Response
import org.http4s.dsl.Http4sDsl
import http4stus.data.Extension
import cats.data.NonEmptyList

private trait Http4sTusDsl[F[_]] extends Http4sDsl[F]:

  extension [F[_]: Monad](self: F[Response[F]])
    def putHeader(h: Option[Header.ToRaw]): F[Response[F]] =
      self.map(r => h.map(r.putHeaders(_)).getOrElse(r))

    def withUploadLength(size: Option[ByteSize]): F[Response[F]] =
      putHeader(size.map(UploadLength.apply))

    def withOffset(offset: ByteSize): F[Response[F]] =
      self.map(_.putHeaders(UploadOffset(offset)))

    def withMaxSize(maxs: Option[ByteSize]): F[Response[F]] =
      putHeader(maxs.map(TusMaxSize.apply))

    def withExtensions(exts: Set[Extension]): F[Response[F]] =
      putHeader(NonEmptyList.fromList(exts.toList).map(TusExtension.apply))

    def withTusResumable: F[Response[F]] =
      self.map(_.putHeaders(TusResumable.V1_0_0))
