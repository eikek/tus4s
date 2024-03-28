package http4stus.server

import cats.effect.Sync
import cats.syntax.all.*

import http4stus.Endpoint
import http4stus.protocol.CoreProtocol
import org.http4s.*
import org.http4s.headers.`Cache-Control`
import http4stus.data.*
import http4stus.protocol.headers.*

final class TusEndpoint[F[_]: Sync](
    core: CoreProtocol[F],
    maxSize: Option[ByteSize],
    extensions: ExtensionConfig[F]
) extends Endpoint[F]
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
      // either upload-defer-length or upload-length
      // must not upload-offset header
      // creation -> must not content-type offset+octet-stream
      // creationWith -> optional offset+octet-stream
      ???
    case OPTIONS -> Root =>
      NoContent
        .headers(TusVersion.V1_0_0)
        .withMaxSize(maxSize)
        .withExtensions(extensions.all)
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

  private def patch(id: UploadId, req: Request[F]): F[Response[F]] = {
    import IdToUploadChunk.given

    for {
      input <- req.as[IdToUploadChunk[F]]
      chunk = input(id)
      resp <- core.receive(chunk).flatMap {
        case ReceiveResult.NotFound => NotFound().withTusResumable
        case ReceiveResult.OffsetMismatch(current) =>
          Conflict(s"Offset does not match, current is $current").withTusResumable
        case ReceiveResult.Success(newOffset) =>
          NoContent().withOffset(newOffset).withTusResumable
      }
    } yield resp
  }

  private def delete(id: UploadId): F[Response[F]] =
    ???

  def app: HttpApp[F] = routes.orNotFound
