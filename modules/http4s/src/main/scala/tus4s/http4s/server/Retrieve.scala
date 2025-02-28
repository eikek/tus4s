package tus4s.http4s.server

import cats.Applicative
import cats.Monad
import cats.data.OptionT

import org.http4s.*
import tus4s.core.TusFinder
import tus4s.core.data.ByteRange
import tus4s.core.data.UploadId

trait Retrieve[F[_]]:
  def find(tus: TusFinder[F], req: Request[F], id: UploadId): F[Response[F]]

object Retrieve:
  def apply[F[_]](
      get: (TusFinder[F], Request[F], UploadId) => F[Response[F]]
  ): Retrieve[F] =
    new Retrieve[F]:
      def find(tus: TusFinder[F], req: Request[F], id: UploadId): F[Response[F]] =
        get(tus, req, id)

  def applyOrNotFound[F[_]: Monad](
      get: (TusFinder[F], Request[F], UploadId) => OptionT[F, Response[F]]
  ): Retrieve[F] =
    apply((tus, req, id) => get(tus, req, id).getOrElseF(Response.notFoundFor(req)))

  def notFound[F[_]: Applicative]: Retrieve[F] =
    apply((_, req, _) => Response.notFoundFor(req))

  def simpleGet[F[_]: Monad]: Retrieve[F] =
    applyOrNotFound { (tus, req, id) =>
      OptionT(tus.find(id, ByteRange.all))
        .filter(_.state.isDone)
        .map(file =>
          RetrieveImpl
            .getETag(req, file)
            .getOrElse(RetrieveImpl.get(file))
        )
    }

  def rangeGet[F[_]: Monad]: Retrieve[F] =
    applyOrNotFound { (tus, req, id) =>
      RetrieveImpl.getRange(req) match
        case Left(r) => OptionT.pure(r)
        case Right(None) =>
          OptionT.liftF(simpleGet.find(tus, req, id))
        case Right(Some(range)) =>
          OptionT(tus.find(id, range))
            .filter(_.state.isDone)
            .map(RetrieveImpl.range[F](_, range))
    }
