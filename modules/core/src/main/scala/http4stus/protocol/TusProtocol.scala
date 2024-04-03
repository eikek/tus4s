package http4stus.protocol

import http4stus.data.*
import cats.data.NonEmptyList

trait TusProtocol[F[_]]:
  /** Supported extensions. */
  def extensions: Set[Extension]

  /** Look up an upload and return its current state. */
  def find(id: UploadId): F[Option[UploadState]]

  /** Receive a chunk of data from the given offset. */
  def receive(chunk: UploadChunk[F]): F[ReceiveResult]

  /** Create a new upload, possibly empty. */
  def create(req: CreationRequest[F]): F[CreationResult]

  /** Delete an upload (finished or not) */
  def delete(id: UploadId): F[Unit]

  /** Concatenate chunks into a final upload, removes partial chunks. */
  def concat(ids: NonEmptyList[UploadId]): F[UploadId]
