package tus4s.http4s.server

import cats.MonadThrow
import cats.data.{EitherT, NonEmptyList}
import cats.effect.Sync
import cats.syntax.all.*
import cats.{Applicative, ApplicativeThrow}
import fs2.Stream

import tus4s.core.data.*
import tus4s.core.TusConfig
import tus4s.http4s.headers.*
import tus4s.http4s.Headers
import org.http4s.*
import org.http4s.headers.`Content-Length`
import org.typelevel.ci.CIString

object TusCodec:
  def forPatch[F[_]: ApplicativeThrow](
      cfg: TusConfig
  ): EntityDecoder[F, UploadRequest[F]] =
    EntityDecoder.decodeBy(Headers.offsetOctetStream) { req =>
      val offset = req.headers
        .get[UploadOffset]
        .toRight(TusDecodeFailure.MissingHeader(UploadOffset.name))
      val uploadLength = validateMaxSize(cfg, req.headers.get[UploadLength].map(_.length))
      val cntLen = req.headers
        .get[`Content-Length`]
        .map(_.length)
      val checksum = validateChecksum(cfg, req.headers.get[UploadChecksum])
      val partial = validateUploadConcat(cfg, req.headers.get[UploadConcat])
        .map(_.exists(_.isPartial))
      val meta =
        req.headers.get[UploadMetadata].map(_.decoded).getOrElse(MetadataMap.empty)
      val data = cntLen.map(len => req.body.take(len)).getOrElse(req.body)

      EitherT.fromEither(
        for
          off <- offset
          ulen <- uploadLength
          cs <- checksum
          part <- partial
        yield UploadRequest(
          offset = off.offset,
          contentLength = cntLen.map(ByteSize.bytes),
          uploadLength = ulen,
          checksum = cs.map(_.toModel),
          isPartial = part,
          meta = meta,
          hasContent = true,
          data = cfg.maxSize match
            case None     => data
            case Some(sz) => LimitStream(data, sz)
        )
      )
    }

  def forCreation[F[_]: MonadThrow](
      cfg: TusConfig,
      creation: Extension.Creation
  ): EntityDecoder[F, UploadRequest[F]] =
    withUpload(cfg, creation).orElse(withoutUpload(cfg, creation))

  def forConcatFinal[F[_]: Applicative](
      cfg: TusConfig,
      baseUri: Option[Uri]
  ): EntityDecoder[F, ConcatRequest] =
    EntityDecoder.decodeBy(MediaRange.`*/*`) { req =>
      req.headers.get[UploadConcat] match
        case Some(UploadConcat(complete: ConcatType.Final)) =>
          if (Extension.hasConcat(cfg.extensions))
            val meta =
              req.headers.get[UploadMetadata].map(_.decoded).getOrElse(MetadataMap.empty)
            EitherT.fromEither(
              complete
                .resolveToId(baseUri)
                .leftMap(TusDecodeFailure.PartialUriError(_))
                .map(ids => ConcatRequest(ids, complete.partials, meta))
            )
          else
            EitherT.leftT(TusDecodeFailure.UnsupportedExtension(Extension.Concatenation))

        case _ =>
          EitherT.leftT(TusDecodeFailure.MissingHeader(UploadConcat.name))
    }

  def forCreationOrConcatFinal[F[_]: Sync](
      cfg: TusConfig,
      baseUri: Option[Uri]
  ): EntityDecoder[F, Either[UploadRequest[F], ConcatRequest]] =
    val d1 =
      Extension.findCreation(cfg.extensions) match
        case Some(creation) =>
          forCreation(cfg, creation).map(_.asLeft[ConcatRequest])
        case None =>
          error(TusDecodeFailure.UnsupportedExtension(Extension.Creation(Set.empty)))
    val d2 = forConcatFinal[F](cfg, baseUri).map(_.asRight[UploadRequest[F]])
    EntityDecoder.decodeBy(MediaRange.`*/*`) { req =>
      req.headers.get[UploadConcat] match
        case Some(_) => d2.decode(req, false)
        case None    => d1.decode(req, false)
    }
    // d1.orElse(d2) doesn't work because it dispatches on mediatype ...

  private def validateChecksum(
      cfg: TusConfig,
      uc: Option[UploadChecksum]
  ): Either[DecodeFailure, Option[UploadChecksum]] =
    uc match
      case Some(cs) if !Extension.includesAlgorithm(cfg.extensions, cs.algorithm) =>
        Left(TusDecodeFailure.UnsupportedChecksumAlgorithm(cs.algorithm))
      case _ => Right(uc)

  private def validateUploadConcat(
      cfg: TusConfig,
      uc: Option[UploadConcat]
  ): Either[DecodeFailure, Option[UploadConcat]] =
    if (uc.isEmpty || Extension.hasConcat(cfg.extensions)) Right(uc)
    else Left(TusDecodeFailure.UnsupportedExtension(Extension.Concatenation))

  private def validateMaxSize(
      cfg: TusConfig,
      size: Option[ByteSize]
  ): Either[DecodeFailure, Option[ByteSize]] =
    (cfg.maxSize, size) match
      case (Some(max), Some(sz)) if max <= sz =>
        Left(TusDecodeFailure.MaxSizeExceeded(max, sz))
      case _ => Right(size)

  private def validateDeferLength[F[_]](
      cfg: TusConfig,
      creation: Extension.Creation,
      req: Media[F]
  ): Either[DecodeFailure, Option[ByteSize]] =
    req.headers.get[UploadLength].map(_.length) match
      case len @ Some(_) => validateMaxSize(cfg, len)
      case None =>
        val defLen = CreationOptions.WithDeferredLength
        val hasDeferredLen = creation.options.contains(defLen)
        req.headers.get[UploadDeferLength] match
          case Some(_) if hasDeferredLen => Right(None)
          case Some(_) =>
            Left(TusDecodeFailure.UnsupportedExtension(Extension.Creation(Set(defLen))))
          case None =>
            val name =
              if (hasDeferredLen)
                UploadLength.name |+| CIString(" or ") |+| UploadDeferLength.name
              else UploadLength.name
            Left(TusDecodeFailure.MissingHeader(name))

  private def requireCreationWithUpload(
      creation: Extension.Creation
  ): Either[DecodeFailure, Unit] =
    if (creation.options.contains(CreationOptions.WithUpload)) Right(())
    else
      Left(
        TusDecodeFailure.UnsupportedExtension(
          Extension.Creation(Set(CreationOptions.WithUpload))
        )
      )

  private def withUpload[F[_]: MonadThrow](
      cfg: TusConfig,
      creation: Extension.Creation
  ): EntityDecoder[F, UploadRequest[F]] =
    EntityDecoder.decodeBy(Headers.offsetOctetStream) { req =>
      for
        result <- forPatch(cfg).decode(req, false)
        _ <- EitherT.fromEither(validateDeferLength(cfg, creation, req))
        _ <- EitherT.fromEither(requireCreationWithUpload(creation))
      yield result
    }

  private def withoutUpload[F[_]: Applicative](
      cfg: TusConfig,
      creation: Extension.Creation
  ): EntityDecoder[F, UploadRequest[F]] =
    EntityDecoder.decodeBy(MediaRange.`*/*`) { req =>
      val uploadLen = validateDeferLength(cfg, creation, req)
      val meta =
        req.headers.get[UploadMetadata].map(_.decoded).getOrElse(MetadataMap.empty)

      EitherT.fromEither(
        for ulen <- uploadLen
        yield UploadRequest(
          offset = ByteSize.zero,
          contentLength = Some(ByteSize.zero),
          uploadLength = ulen,
          checksum = None,
          isPartial = false,
          meta = meta,
          hasContent = false,
          Stream.empty
        )
      )
    }

  private def error[F[_]: Sync, T](t: Throwable): EntityDecoder[F, T] =
    new EntityDecoder[F, T]:
      override def decode(m: Media[F], strict: Boolean): DecodeResult[F, T] =
        DecodeResult(m.body.compile.drain *> Sync[F].raiseError(t))
      override def consumes: Set[MediaRange] = Set.empty

  extension (self: ConcatType.Final)
    /** Extracts upload ids from the given uris. It uses the last segment of the uri path.
      * If a base-uri is specified, each url is checked whether both authorities match.
      */
    def resolveToId(baseUri: Option[Uri]): Either[String, NonEmptyList[UploadId]] =
      def toUri(url: Url) = Uri.fromString(url.asString).leftMap(_.getMessage)
      def toId(uri: Uri) =
        uri.path.segments.lastOption
          .map(_.decoded())
          .toList
          .traverse(UploadId.fromString)

      def isDescendent(uri: Uri): Boolean =
        baseUri.forall { base =>
          uri.authority == base.authority && uri.path.startsWith(base.path)
        }

      self.partials.toList
        .traverse(toUri)
        .flatMap { uris =>
          uris.flatTraverse { uri =>
            if (!isDescendent(uri))
              Left(s"Partial url not valid for this endpoint: ${uri.renderString}")
            else toId(uri)
          }
        }
        .flatMap(ids => NonEmptyList.fromList(ids).toRight(s"No upload ids available"))
