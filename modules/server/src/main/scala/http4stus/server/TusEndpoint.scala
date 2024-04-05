package http4stus.server

import cats.effect.Sync
import cats.syntax.all.*

import http4stus.Endpoint
import http4stus.data.*
import http4stus.protocol.*
import http4stus.protocol.headers.*
import org.http4s.*
import org.http4s.headers.Location
import org.http4s.headers.`Cache-Control`
import org.http4s.implicits.*

final class TusEndpoint[F[_]: Sync](tus: TusProtocol[F], baseUri: Option[Uri])
    extends Endpoint[F]
    with Http4sTusDsl[F]:

  def routes: HttpRoutes[F] = HttpRoutes.of {
    case HEAD -> Root / UploadId(id)        => head(id)
    case req @ PATCH -> Root / UploadId(id) => patch(id, req)
    case req @ POST -> Root / UploadId(id) =>
      req.headers.get[XHttpMethodOverride].map(_.method) match
        case Some(PATCH)  => patch(id, req)
        case Some(DELETE) => delete(id)
        case Some(HEAD)   => head(id)
        case _            => NotFound()
    case req @ POST -> Root =>
      req.headers.get[UploadConcat] match
        case Some(UploadConcat(complete: ConcatType.Final)) =>
          if (Extension.noConcat(tus.config.extensions)) BadRequest()
          else ???
        case _ =>
          Extension.findCreation(tus.config.extensions) match
            case Some(creation) =>
              for {
                input <- req.as[UploadRequest[F]](using
                  Sync[F],
                  TusCodec.forCreation(tus.config, creation)
                )
                resp <- tus.create(input).flatMap {
                  case CreationResult.Success(id, offset, expires) =>
                    val base = baseUri.getOrElse(uri"")
                    Created
                      .headers(Location(base / id))
                      .withOffset(offset)
                      .withExpires(expires)
                      .withTusResumable
                }
              } yield resp
            case None =>
              NotFound()

    case OPTIONS -> Root =>
      NoContent
        .headers(TusVersion.V1_0_0)
        .withMaxSize(tus.config.maxSize)
        .withExtensions(tus.config.extensions)
        .withTusResumable
    case DELETE -> Root / UploadId(id) =>
      delete(id)
  }

  private def head(id: UploadId): F[Response[F]] =
    tus.find(id).flatMap {
      case Some(upload) =>
        NoContent
          .headers(`Cache-Control`(CacheDirective.`no-store`))
          .withOffset(upload.offset)
          .withUploadLength(upload.length)
          .withTusResumable
          .withMetadata(upload.meta)

      case None => NotFound().withTusResumable
    }

  private def patch(id: UploadId, req: Request[F]): F[Response[F]] =
    for {
      input <- req.as[UploadRequest[F]](using Sync[F], TusCodec.forPatch[F](tus.config))
      resp <- tus.receive(id, input).flatMap {
        case ReceiveResult.NotFound => NotFound().withTusResumable
        case ReceiveResult.OffsetMismatch(current) =>
          Conflict(s"Offset does not match, current is $current").withTusResumable
        case ReceiveResult.UploadLengthMismatch =>
          Conflict(s"Upload length has already been specified")
        case ReceiveResult.ChecksumMismatch =>
          Response(checksumMismatch).pure[F]
        case ReceiveResult.Success(newOffset, expires) =>
          NoContent().withOffset(newOffset).withTusResumable.withExpires(expires)
      }
    } yield resp

  private def delete(id: UploadId): F[Response[F]] =
    tus.delete(id) >> NoContent().withTusResumable

  def app: HttpApp[F] = routes.orNotFound
