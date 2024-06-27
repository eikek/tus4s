package tus4s.server

import cats.Applicative
import cats.Monad
import cats.data.OptionT

import tus4s.data.ByteSize
import tus4s.data.MetadataMap.Key
import tus4s.data.UploadId
import tus4s.protocol.TusProtocol
import tus4s.protocol.headers.UploadLength
import tus4s.protocol.headers.UploadMetadata
import org.http4s.*
import org.http4s.headers.*
import org.typelevel.ci.*

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
              file.getContentType.map(`Content-Type`(_)),
              file.state.meta
                .getString(Key.fileName)
                .map(n => `Content-Disposition`("inline", Map(ci"filename" -> n)))
            )
          )
        )
      yield resp).getOrElseF(Response.notFoundFor(req))
    }
