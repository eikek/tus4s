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
import cats.data.OptionT
import http4stus.data.MetadataMap.Key
import org.http4s.headers.*
import org.typelevel.ci.*

final class TusEndpoint[F[_]: Sync](
    tus: TusProtocol[F],
    allowRetrieve: Boolean,
    baseUri: Option[Uri]
) extends Endpoint[F]
    with Http4sTusDsl[F]:

  def routes: HttpRoutes[F] = HttpRoutes.of {
    case HEAD -> Root / UploadId(id)        => head(id)
    case req @ PATCH -> Root / UploadId(id) => patch(id, req)
    case GET -> Root / UploadId(id) if allowRetrieve =>
      (for
        file <- OptionT(tus.find(id)).filter(_.state.isDone)
        ct = file.getContentType.map(`Content-Type`(_))
        fn = file.state.meta
          .getString(Key.fileName)
          .map(n => `Content-Disposition`("inline", Map(ci"filename" -> n)))
        resp <- OptionT.liftF(
          Ok(file.data).withMetadata(file.state.meta).withUploadLength(file.state.length)
        )
      yield resp.putHeaders(ct, fn)).getOrElseF(NotFound())

    case req @ POST -> Root / UploadId(id) =>
      req.headers.get[XHttpMethodOverride].map(_.method) match
        case Some(PATCH)  => patch(id, req)
        case Some(DELETE) => delete(id)
        case Some(HEAD)   => head(id)
        case _            => NotFound()
    case req @ POST -> Root =>
      for {
        input <- req.as[Either[UploadRequest[F], ConcatRequest]](using
          Sync[F],
          TusCodec.forCreationOrConcatFinal[F](tus.config, baseUri)
        )
        resp <- input.fold(create(req.httpVersion), concatenate(_))
      } yield resp
    case OPTIONS -> Root =>
      NoContent
        .headers(TusVersion.V1_0_0)
        .withMaxSize(tus.config.maxSize)
        .withExtensions(tus.config.extensions)
        .withTusResumable
    case DELETE -> Root / UploadId(id) =>
      delete(id)
  }

  private def create(hv: HttpVersion)(req: UploadRequest[F]): F[Response[F]] =
    tus.create(req).flatMap {
      case CreationResult.ChecksumMismatch =>
        Response(checksumMismatch).pure[F]
      case CreationResult.UploadTooLarge(maxSize, current) =>
        TusDecodeFailure.MaxSizeExceeded(maxSize, current).toHttpResponse(hv).pure[F]
      case CreationResult.Success(id, offset, expires) =>
        val base = baseUri.getOrElse(uri"")
        Created
          .headers(Location(base / id))
          .withOffset(offset)
          .withExpires(expires)
          .withTusResumable
    }

  private def concatenate(req: ConcatRequest): F[Response[F]] =
    tus.concat(req).flatMap {
      case ConcatResult.Success(id) =>
        val base = baseUri.getOrElse(uri"")
        Created.headers(Location(base / id)).withTusResumable

      case ConcatResult.PartsNotFound(ids) =>
        UnprocessableEntity(s"Some partials could not be found: $ids").withTusResumable
    }

  private def head(id: UploadId): F[Response[F]] =
    tus.find(id).flatMap {
      case Some(upload) =>
        NoContent
          .headers(`Cache-Control`(CacheDirective.`no-store`))
          .withOffset(upload.state.offset)
          .withUploadLength(upload.state.length)
          .withTusResumable
          .withMetadata(upload.state.meta)
          .withConcatType(upload.state.concatType)

      case None => NotFound().withTusResumable
    }

  private def patch(id: UploadId, req: Request[F]): F[Response[F]] =
    for {
      input <- req.as[UploadRequest[F]](using
        Sync[F],
        TusCodec.forPatch[F](tus.config)
      )
      resp <- tus.receive(id, input).flatMap {
        case ReceiveResult.NotFound => NotFound().withTusResumable
        case ReceiveResult.OffsetMismatch(current) =>
          Conflict(s"Offset does not match, current is $current").withTusResumable
        case ReceiveResult.UploadLengthMismatch =>
          Conflict(s"Upload length has already been specified")
        case ReceiveResult.UploadDone =>
          Conflict(s"Upload is already done")
        case ReceiveResult.ChecksumMismatch =>
          Response(checksumMismatch).pure[F]
        case ReceiveResult.UploadTooLarge(max, current) =>
          TusDecodeFailure
            .MaxSizeExceeded(max, current)
            .toHttpResponse(req.httpVersion)
            .pure[F]
        case ReceiveResult.Success(newOffset, expires) =>
          NoContent().withOffset(newOffset).withTusResumable.withExpires(expires)
        case ReceiveResult.UploadIsFinal =>
          Forbidden("Patch against a final upload").withTusResumable
      }
    } yield resp

  private def delete(id: UploadId): F[Response[F]] =
    tus.delete(id) >> NoContent().withTusResumable

  def app: HttpApp[F] = routes.orNotFound
