package http4stus.server

import cats.Monad
import cats.syntax.all.*

import http4stus.data.ByteSize
import http4stus.protocol.headers.*
import org.http4s.Header
import org.http4s.Response
import org.http4s.dsl.Http4sDsl

private trait Http4sTusDsl[F[_]] extends Http4sDsl[F]:

  extension [F[_]: Monad](self: F[Response[F]])
    def putHeader(h: Option[Header.ToRaw]): F[Response[F]] =
      self.map(r => h.map(r.putHeaders(_)).getOrElse(r))

    def withUploadLength(size: Option[ByteSize]): F[Response[F]] =
      putHeader(size.map(UploadLength.apply))

    def withOffset(offset: ByteSize): F[Response[F]] =
      self.map(_.putHeaders(UploadOffset(offset)))
