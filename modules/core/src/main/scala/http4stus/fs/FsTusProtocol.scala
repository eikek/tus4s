package http4stus.fs

import cats.effect.Sync
import cats.syntax.all.*
import fs2.io.file.{Files, Path}

import http4stus.data.*
import http4stus.protocol.TusConfig
import http4stus.protocol.TusProtocol
import cats.data.OptionT

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
          if (state.offset != chunk.offset) ReceiveResult.OffsetMismatch(state.offset).pure[F]
          else if (state.length.isDefined && chunk.uploadLength != state.length)
            ReceiveResult.UploadLengthMismatch.pure[F]
          else if (state.isDone) ReceiveResult.UploadDone.pure[F]
          else if (state.isFinal) ReceiveResult.UploadIsFinal.pure[F]
          else e.writeChunk(chunk.data)
        }
    }

  def create(req: UploadRequest[F]): F[CreationResult] = ???

  def delete(id: UploadId): F[Unit] =
    UploadEntry.delete(dir, id)

  def concat(req: ConcatRequest): F[ConcatResult] = ???
