package tus4s.http4s.server

import cats.Applicative
import cats.Monad
import cats.data.OptionT

import org.http4s.*
import org.http4s.headers.*
import org.typelevel.ci.*
import tus4s.core.TusProtocol
import tus4s.core.data.ByteSize
import tus4s.core.data.UploadId
import tus4s.http4s.headers.UploadLength
import tus4s.http4s.headers.UploadMetadata

trait Retrieve[F[_]]:
  def find(tus: TusProtocol[F], req: Request[F], id: UploadId): F[Response[F]]

object Retrieve:
  def apply[F[_]](
      get: (TusProtocol[F], Request[F], UploadId) => F[Response[F]]
  ): Retrieve[F] =
    new Retrieve[F]:
      def find(tus: TusProtocol[F], req: Request[F], id: UploadId): F[Response[F]] =
        get(tus, req, id)

  def notFound[F[_]: Applicative]: Retrieve[F] =
    apply((_, req, _) => Response.notFoundFor(req))

  def simpleGet[F[_]: Monad]: Retrieve[F] =
    apply { (tus, req, id) =>
      (for
        file <- OptionT(tus.find(id)).filter(_.state.isDone)
        resp <- OptionT.pure(
          Response(
            body = file.data,
            headers = Headers(
              UploadMetadata(file.state.meta),
              UploadLength(file.state.length.getOrElse(ByteSize.zero)),
              `Content-Length`(file.state.length.get.toBytes),
              file.getContentType
                .flatMap(ct => MediaType.parse(ct).toOption)
                .map(`Content-Type`(_)),
              file.getFileName
                .map(n => `Content-Disposition`("inline", Map(ci"filename" -> n)))
            )
          )
        )
      yield resp).getOrElseF(Response.notFoundFor(req))
    }
