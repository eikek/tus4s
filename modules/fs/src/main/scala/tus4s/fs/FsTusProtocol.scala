package tus4s.fs

import cats.data.NonEmptyList
import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}

import tus4s.core.TusConfig
import tus4s.core.TusProtocol
import tus4s.core.data.*
import tus4s.core.internal.Validation

final class FsTusProtocol[F[_]: Sync: Files](
    dir: Path,
    maxSize: Option[ByteSize],
    enableTermination: Boolean = true,
    enableConcatenation: Boolean = true,
    enableChecksum: Boolean = true
) extends TusProtocol[F]:
  val config: TusConfig = TusConfig(
    extensions = Extension.createSet(
      Extension.Creation(CreationOptions.all) -> true,
      Extension.Termination -> enableTermination,
      Extension.Checksum(ChecksumAlgorithm.all) -> enableChecksum,
      Extension.Concatenation -> enableConcatenation
    ),
    maxSize = maxSize,
    rangeRequests = true
  )

  def findFile(id: UploadId): F[Option[(UploadState, Path)]] =
    OptionT(UploadEntry.find[F](dir, id))
      .semiflatMap(e => e.readState[F].map(state => (state, e.dataFile)))
      .value

  def find(id: UploadId, range: ByteRange): F[Option[FileResult[F]]] =
    OptionT(findFile(id)).semiflatMap { case (state, file) =>
      val (data, hasContent) = range.fold(
        (Files[F].readAll(file), state.length.exists(_ > ByteSize.zero)),
        chunk =>
          if (chunk.isEmpty) (Stream.empty, false)
          else
            (
              Files[F].readRange(
                file,
                64 * 1024,
                chunk.offset.toBytes,
                chunk.length.map(_ + chunk.offset).map(_.toBytes).getOrElse(Long.MaxValue)
              ),
              true
            )
      )
      FileResult(state, data, hasContent, None, None).pure[F]
    }.value

  def receive(id: UploadId, chunk: UploadRequest[F]): F[ReceiveResult] =
    UploadEntry
      .find[F](dir, id)
      .flatMap:
        case None => ReceiveResult.NotFound.pure[F]
        case Some(e) =>
          e.readState[F].flatMap { state =>
            receiveData(e, state, chunk)
          }

  private def receiveData(
      e: UploadEntry,
      state: UploadState,
      chunk: UploadRequest[F]
  ): F[ReceiveResult] =
    Validation.validateReceive(state, chunk, maxSize).map(_.pure[F]).getOrElse {
      e.writeChunk(chunk.checksum.map(_.algorithm), chunk.dataLimit(maxSize)).use { temp =>
        Validation
          .validateReceive(state, chunk.copy(contentLength = temp.length.some), maxSize)
          .map(_.pure[F])
          .getOrElse {
            val newState = state.copy(
              offset = temp.length + state.offset,
              length = state.length.orElse(chunk.uploadLength)
            )
            val checksumMismatch = chunk.checksum
              .filter(_.hash != temp.hash)
              .map(_ => ReceiveResult.ChecksumMismatch.pure[F])

            checksumMismatch.getOrElse:
              (e.append(temp, newState) >> e.writeState(newState))
                .as(ReceiveResult.Success(newState.offset, None))
          }
      }
    }

  private def makeNewEntry =
    UploadId.randomULID[F].flatMap(UploadEntry.create(dir, _))

  def create(req: UploadRequest[F]): F[CreationResult] =
    Validation
      .validateCreate(req, maxSize)
      .map(_.pure[F])
      .getOrElse:
        makeNewEntry.flatMap { e =>
          val stateNoContent = req.toStateNoContent(e.id)
          if (req.hasContent)
            e.writeChunk(req.checksum.map(_.algorithm), req.dataLimit(maxSize)).use {
              temp =>
                Validation.validateCreate(
                  req.copy(contentLength = temp.length.some),
                  maxSize
                ) match
                  case Some(r) => r.pure[F]
                  case None =>
                    val state = stateNoContent.copy(offset = temp.length)
                    val checksumMismatch = req.checksum
                      .filter(_.hash != temp.hash)
                      .map(_ => CreationResult.ChecksumMismatch.pure[F])

                    checksumMismatch.getOrElse:
                      (e.append(temp, state) >> e.writeState(state))
                        .as(CreationResult.Success(e.id, state.offset, None))
            }
          else
            e.writeState(stateNoContent)
              .as(CreationResult.Success(e.id, ByteSize.zero, None))
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
    entries.flatMap:
      case (Nil, parts) =>
        UploadEntry
          .copyAll(dir, parts, req.uris, req.meta)
          .map(state => ConcatResult.Success(state.id))
      case (missing, _) =>
        ConcatResult.PartsNotFound(NonEmptyList.fromListUnsafe(missing)).pure[F]

object FsTusProtocol:
  def create[F[_]: Files: Sync](
      dir: Path,
      maxSize: Option[ByteSize],
      enableTermination: Boolean = true,
      enableConcatenation: Boolean = true,
      enableChecksum: Boolean = true
  ): F[FsTusProtocol[F]] =
    Files[F]
      .createDirectories(dir)
      .map(_ =>
        new FsTusProtocol[F](
          dir,
          maxSize,
          enableTermination,
          enableConcatenation,
          enableChecksum
        )
      )
