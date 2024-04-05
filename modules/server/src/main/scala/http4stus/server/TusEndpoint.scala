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
      // either concat-final or creation
      Extension.findCreation(tus.config.extensions) match
        case Some(Extension.Creation(opts)) =>
          for {
            input <- req.as[CreationRequest[F]]
            // support defer-lenght?
            fail1 = Option.when(
              input.uploadLength.isEmpty && !opts.contains(
                CreationOptions.WithDeferredLength
              )
            )(BadRequest("Upload-Defer-Length not supported"))
            // supports with-upload?
            fail2 = Option.when(
              input.hasContent && !opts.contains(CreationOptions.WithUpload)
            )(
              BadRequest("Creation with upload not supported")
            )
            // max size exceeded?
            fail3 = tus.config.maxSize
              .filter(m => input.uploadLength.exists(len => len >= m))
              .map(_ => PayloadTooLarge(s"max size is ${tus.config.maxSize}"))

            wrongAlgo = input.checksum.exists(cs =>
              !Extension.includesAlgorithm(tus.config.extensions, cs.algorithm)
            )
            fail4 = Option.when(wrongAlgo)(
              BadRequest(
                s"Checksum algorithm ${input.checksum.map(_.algorithm)} not supported"
              )
            )
            fail5 = Option.when(
              input.isPartial && Extension.noConcat(tus.config.extensions)
            )(
              BadRequest(s"Concatenation and partial chunks not supported")
            )

            resp <- (fail1 <+> fail2 <+> fail3).getOrElse(tus.create(input).flatMap {
              case CreationResult.Success(id, offset, expires) =>
                val base = baseUri.getOrElse(uri"")
                Created
                  .headers(Location(base / id))
                  .withOffset(offset)
                  .withExpires(expires)
                  .withTusResumable
            })
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

  private def patch(id: UploadId, req: Request[F]): F[Response[F]] = {
    import IdToPatchRequest.given

    for {
      input <- req.as[IdToPatchRequest[F]]
      chunk = input(id)
      wrongAlgo = chunk.checksum.exists(cs =>
        !Extension.includesAlgorithm(tus.config.extensions, cs.algorithm)
      )
      fail1 = Option.when(wrongAlgo)(
        BadRequest(s"Checksum algorithm ${chunk.checksum.map(_.algorithm)} not supported")
      )
      fail2 = Option.when(chunk.isPartial && Extension.noConcat(tus.config.extensions))(
        BadRequest(s"Concatenation and partial chunks not supported")
      )
      fail3 = tus.config.maxSize
        .filter(m => chunk.uploadLength.exists(len => len >= m))
        .map(_ => PayloadTooLarge(s"max size is ${tus.config.maxSize}"))

      resp <- (fail1 <+> fail2 <+> fail3).getOrElse(tus.receive(chunk).flatMap {
        case ReceiveResult.NotFound => NotFound().withTusResumable
        case ReceiveResult.OffsetMismatch(current) =>
          Conflict(s"Offset does not match, current is $current").withTusResumable
        case ReceiveResult.UploadLengthMismatch =>
          Conflict(s"Upload length has already been specified")
        case ReceiveResult.ChecksumMismatch =>
          Response(checksumMismatch).pure[F]
        case ReceiveResult.Success(newOffset, expires) =>
          NoContent().withOffset(newOffset).withTusResumable.withExpires(expires)
      })
    } yield resp
  }

  private def delete(id: UploadId): F[Response[F]] =
    tus.delete(id) >> NoContent().withTusResumable

  def app: HttpApp[F] = routes.orNotFound
