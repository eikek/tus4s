package tus4s.pg

import cats.effect.*

import tus4s.core.*
import tus4s.core.data.*

class PgTusProtocol[F[_]: Sync](cfg: PgConfig[F]) extends TusProtocol[F]:
  private val tasks = PgTasks[F](cfg.table)

  /** Configuration supported by this protocol implementation. */
  def config: TusConfig = TusConfig(
    extensions = Set(
      Extension.Creation(CreationOptions.all),
      Extension.Termination,
      Extension.Concatenation
    ),
    maxSize = cfg.maxSize
  )

  /** Look up an upload and return its current state. */
  def find(id: UploadId): F[Option[FileResult[F]]] =
    cfg.db.use(tasks.findFile(id, cfg.chunkSize.toBytes.toInt).run)

  /** Receive a chunk of data from the given offset. */
  def receive(id: UploadId, chunk: UploadRequest[F]): F[ReceiveResult] = ???

  /** Create a new upload, possibly empty. */
  def create(req: UploadRequest[F]): F[CreationResult] =
    cfg.db.use(tasks.createUpload(req, cfg.chunkSize.toBytes.toInt).run)

  /** Delete an upload (finished or not) */
  def delete(id: UploadId): F[Unit] = ???

  /** Concatenate chunks into a final upload, may remove partial uploads. */
  def concat(req: ConcatRequest): F[ConcatResult] = ???
