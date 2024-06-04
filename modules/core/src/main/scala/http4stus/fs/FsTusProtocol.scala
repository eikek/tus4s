package http4stus.fs

import cats.data.NonEmptyList
import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all.*
import fs2.io.file.{Files, Path}

import http4stus.data.*
import http4stus.protocol.TusConfig
import http4stus.protocol.TusProtocol

final class FsTusProtocol[F[_]: Sync: Files](dir: Path, maxSize: Option[ByteSize])
    extends TusProtocol[F]:
  def config: TusConfig = TusConfig(
    extensions = Set(
      Extension.Creation(CreationOptions.all),
      Extension.Checksum(ChecksumAlgorithm.all),
      Extension.Termination,
      Extension.Concatenation
    ),
    maxSize = maxSize
  )

  def findFile(id: UploadId): F[Option[(UploadState, Path)]] =
    OptionT(UploadEntry.find[F](dir, id))
      .semiflatMap(e => e.readState[F].map(state => (state, e.dataFile)))
      .value

  def find(id: UploadId): F[Option[FileResult[F]]] =
    OptionT(findFile(id)).semiflatMap { case (state, file) =>
      val hasContent = state.length.exists(_ > ByteSize.zero)
      FileResult(state, Files[F].readAll(file), hasContent, None, None).pure[F]
    }.value

  def receive(id: UploadId, chunk: UploadRequest[F]): F[ReceiveResult] =
    whenExceedsMaxSize(chunk.uploadLength)(
      ReceiveResult.UploadTooLarge(_, _).pure[F]
    ).getOrElse {
      UploadEntry.find[F](dir, id).flatMap {
        case None => ReceiveResult.NotFound.pure[F]
        case Some(e) =>
          e.readState[F].flatMap { state =>
            receiveData(e, state, chunk)
          }
      }
    }

  private def receiveData(
      e: UploadEntry,
      state: UploadState,
      chunk: UploadRequest[F]
  ): F[ReceiveResult] =
    if (state.offset != chunk.offset)
      ReceiveResult.OffsetMismatch(state.offset).pure[F]
    else if (state.length.exists(l1 => chunk.uploadLength.exists(l2 => l1 != l2)))
      ReceiveResult.UploadLengthMismatch.pure[F]
    else if (state.isDone) ReceiveResult.UploadDone.pure[F]
    else if (state.isFinal) ReceiveResult.UploadIsFinal.pure[F]
    else
      e.writeChunk(chunk.data).flatMap { temp =>
        val newState = state.copy(
          offset = temp.length + state.offset,
          length = state.length.orElse(chunk.uploadLength),
          concatType = Option.when(chunk.isPartial)(ConcatType.Partial)
        )
        val checksumMismatch =
          OptionT
            .fromOption[F](chunk.checksum)
            .semiflatMap(uc => e.createChecksum(temp, uc.algorithm).map(_ == uc.checksum))
            .subflatMap(ok => Option.when(!ok)(ReceiveResult.ChecksumMismatch))

        checksumMismatch.getOrElseF {
          (e.append(temp, newState) >> e.writeState(newState))
            .as(ReceiveResult.Success(newState.offset, None))
        }
      }

  private def makeNewEntry =
    UploadId.randomUUID[F].flatMap(UploadEntry.create(dir, _))

  private def whenExceedsMaxSize[A](
      size: Option[ByteSize]
  )(a: (ByteSize, ByteSize) => A): Option[A] =
    for {
      max <- maxSize
      cur <- size
      if size >= maxSize
    } yield a(max, cur)

  def create(req: UploadRequest[F]): F[CreationResult] =
    whenExceedsMaxSize(req.uploadLength)(
      CreationResult.UploadTooLarge(_, _).pure[F]
    ).getOrElse {
      makeNewEntry.flatMap { e =>
        val stateNoContent = UploadState(
          e.id,
          ByteSize.zero,
          req.uploadLength,
          req.meta,
          Option.when(req.isPartial)(ConcatType.Partial)
        )

        if (req.hasContent)
          e.writeChunk(req.data).flatMap { temp =>
            val state = stateNoContent.copy(offset = temp.length)
            val checksumMismatch =
              OptionT
                .fromOption[F](req.checksum)
                .semiflatMap(uc =>
                  e.createChecksum(temp, uc.algorithm).map(_ == uc.checksum)
                )
                .subflatMap(ok => Option.when(!ok)(CreationResult.ChecksumMismatch))

            checksumMismatch.getOrElseF {
              (e.append(temp, state) >> e.writeState(state))
                .as(CreationResult.Success(e.id, state.offset, None))
            }
          }
        else
          e.writeState(stateNoContent)
            .as(CreationResult.Success(e.id, ByteSize.zero, None))
      }
    }

  def delete(id: UploadId): F[Unit] =
    UploadEntry.delete(dir, id)

  def concat(req: ConcatRequest): F[ConcatResult] =
    val entries = req.ids.toList
      .traverse(id =>
        UploadEntry.findWithState[F](dir, id).map {
          case Some((entry, state)) if state.isPartial => Right(entry)
          case _                                       => Left(id)
        }
      )
      .map(_.partitionMap(identity))
    entries.flatMap {
      case (Nil, parts) =>
        UploadEntry
          .copyAll(dir, parts, req.uris, req.meta)
          .map(state => ConcatResult.Success(state.id))
      case (missing, _) =>
        ConcatResult.PartsNotFound(NonEmptyList.fromListUnsafe(missing)).pure[F]
    }

object FsTusProtocol:
  def create[F[_]: Files: Sync](
      dir: Path,
      maxSize: Option[ByteSize]
  ): F[FsTusProtocol[F]] =
    Files[F].createDirectories(dir).map(_ => new FsTusProtocol[F](dir, maxSize))
