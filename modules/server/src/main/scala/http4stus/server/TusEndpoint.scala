package http4stus.server

import cats.effect.Sync
import cats.syntax.all.*

import http4stus.CoreProtocol
import http4stus.Endpoint
import http4stus.data.UploadId
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.headers.`Cache-Control`
import org.http4s.CacheDirective
import org.http4s.Header

final class TusEndpoint[F[_]: Sync](core: CoreProtocol[F])
    extends Endpoint[F]
    with Http4sTusDsl[F]:
  def routes: HttpRoutes[F] = HttpRoutes.of {
    case HEAD -> Root / UploadId(id) =>
      core.find(id).flatMap {
        case Some(upload) =>
          NoContent
            .headers(`Cache-Control`(CacheDirective.`no-store`))
            .withOffset(upload.offset)
            .withUploadLength(upload.length)

        case None => NotFound()
      }

    case PATCH -> Root / UploadId(id) =>

      ???

    case POST -> Root / UploadId(id) =>
      // treat as PATCH
      ???

    case OPTIONS -> Root =>
      ???

    case DELETE -> Root / UploadId(id) =>
      ???
  }

  def app: HttpApp[F] = routes.orNotFound
