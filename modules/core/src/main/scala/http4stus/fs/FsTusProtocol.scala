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

  def find(id: UploadId): F[Option[UploadState]] =
    OptionT(UploadEntry.find[F](dir, id)).semiflatMap(_.readState[F]).value

  def receive(id: UploadId, chunk: UploadRequest[F]): F[ReceiveResult] =
    UploadEntry.find[F](dir, id).flatMap {
      case None => ReceiveResult.NotFound.pure[F]
      case Some(e) =>
        e.readState[F].flatMap { state =>
          if (state.offset != chunk.offset)
            ReceiveResult.OffsetMismatch(state.offset).pure[F]
          else if (state.length.exists(l1 => chunk.uploadLength.exists(l2 => l1 != l2)))
            ReceiveResult.UploadLengthMismatch.pure[F]
          else if (state.isDone) ReceiveResult.UploadDone.pure[F]
          else if (state.isFinal) ReceiveResult.UploadIsFinal.pure[F]
          else
            e.writeChunk(chunk.data).flatMap { temp =>
              val newState = state.copy(
                offset = temp.length + state.offset
              )
              val checksumMismatch =
                OptionT
                  .fromOption[F](chunk.checksum)
                  .semiflatMap(uc =>
                    e.createChecksum(temp, uc.algorithm).map(_ == uc.checksum)
                  )
                  .subflatMap(ok => Option.when(!ok)(ReceiveResult.ChecksumMismatch))

              checksumMismatch.getOrElseF {
                e.append(temp, newState)
                  .as(ReceiveResult.Success(newState.offset, None))
              }
            }
        }
    }

  private def makeNewEntry =
    UploadId.randomUUID[F].flatMap(UploadEntry.create(dir, _))

  def create(req: UploadRequest[F]): F[CreationResult] =
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
            e.append(temp, state)
              .as(CreationResult.Success(e.id, state.offset, None))
          }
        }
      else
        e.writeState(stateNoContent).as(CreationResult.Success(e.id, ByteSize.zero, None))
    }

  def delete(id: UploadId): F[Unit] =
    UploadEntry.delete(dir, id)

  def concat(req: ConcatRequest): F[ConcatResult] =
    val entries = req.ids
      .traverse(id => UploadEntry.find(dir, id).map(_.toRight(id)))
      .map(_.toList.partitionMap(identity))
    entries.flatMap {
      case (Nil, parts) =>
        UploadEntry
          .copyAll(dir, parts, req.uris, req.meta)
          .map(state => ConcatResult.Success(state.id))
      case (missing, _) =>
        ConcatResult.PartsNotFound(NonEmptyList.fromListUnsafe(missing)).pure[F]
    }
