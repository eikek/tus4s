package tus4s.core

import tus4s.core.data.*

trait TusProtocol[F[_]]:
  /** Configuration supported by this protocol implementation. */
  def config: TusConfig

  /** Look up an upload and return its current state. */
  def find(id: UploadId): F[Option[FileResult[F]]]

  /** Receive a chunk of data from the given offset. */
  def receive(id: UploadId, chunk: UploadRequest[F]): F[ReceiveResult]

  /** Create a new upload, possibly empty. */
  def create(req: UploadRequest[F]): F[CreationResult]

  /** Delete an upload (finished or not) */
  def delete(id: UploadId): F[Unit]

  /** Concatenate chunks into a final upload, may remove partial uploads. */
  def concat(req: ConcatRequest): F[ConcatResult]
