package http4stus.server

import http4stus.Endpoint
import org.http4s.dsl.Http4sDsl
import org.http4s.HttpRoutes
import http4stus.data.UploadId
import cats.effect.Sync
import org.http4s.HttpApp

final class TusEndpoint[F[_]: Sync] extends Endpoint[F] with Http4sDsl[F]:
  def routes: HttpRoutes[F] = HttpRoutes.of {
    case HEAD -> Root / UploadId(id) =>
      // + Upload-Offset
      // + Upload-Length (if known)
      // 404 or 410 if not found (no Upload-Offset)
      // 204 No Content
      // Cache-Control: no
      ???

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
