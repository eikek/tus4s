package http4stus.protocol

import cats.data.NonEmptyList

import http4stus.data.*

trait TusProtocol[F[_]]:
  /** Configuration supported by this protocol implementation. */
  def config: TusConfig

  /** Look up an upload and return its current state. */
  def find(id: UploadId): F[Option[UploadState]]

  /** Receive a chunk of data from the given offset. */
  def receive(chunk: PatchRequest[F]): F[ReceiveResult]

  /** Create a new upload, possibly empty. */
  def create(req: CreationRequest[F]): F[CreationResult]

  /** Delete an upload (finished or not) */
  def delete(id: UploadId): F[Unit]

  /** Concatenate chunks into a final upload, removes partial chunks. */
  def concat(ids: NonEmptyList[UploadId]): F[UploadId]
