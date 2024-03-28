package http4stus.server

import cats.effect.Sync
import cats.syntax.all.*

import http4stus.CoreProtocol
import http4stus.Endpoint
import org.http4s.headers.`Cache-Control`
import org.http4s.*
import http4stus.protocol.Headers
import http4stus.protocol.headers.XHttpMethodOverride
import http4stus.protocol.headers.UploadOffset
import http4stus.data.*
import http4stus.protocol.headers.TusVersion
import http4stus.protocol.headers.UploadLength

final class TusEndpoint[F[_]: Sync](core: CoreProtocol[F], maxSize: Option[ByteSize])
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
    case OPTIONS -> Root =>
      NoContent
        .headers(TusVersion.V1_0_0)
        .withMaxSize(maxSize)
        .withTusResumable
    case DELETE -> Root / UploadId(id) =>
      delete(id)
  }

  private def head(id: UploadId): F[Response[F]] =
    core.find(id).flatMap {
      case Some(upload) =>
        NoContent
          .headers(`Cache-Control`(CacheDirective.`no-store`))
          .withOffset(upload.offset)
          .withUploadLength(upload.length)
          .withTusResumable

      case None => NotFound().withTusResumable
    }

  private def patch(id: UploadId, req: Request[F]): F[Response[F]] =
    if (!Headers.isOffsetOctetStream(req)) UnsupportedMediaType()
    else
      req.headers.get[UploadOffset].map(_.offset) match
        case Some(offset) =>
          val length = req.headers.get[UploadLength].map(_.length)
          core.receive(UploadChunk(id, offset, length, req.body)).flatMap {
            case ReceiveResult.NotFound => NotFound().withTusResumable
            case ReceiveResult.OffsetMismatch(current) =>
              Conflict(
                s"Offset does not match: got $offset, but current is $current"
              ).withTusResumable

            case ReceiveResult.Success(newOffset) =>
              NoContent().withOffset(newOffset).withTusResumable

          }
        case None =>
          BadRequest("no Upload-Offset header").withTusResumable

  private def delete(id: UploadId): F[Response[F]] =
    ???

  def app: HttpApp[F] = routes.orNotFound
