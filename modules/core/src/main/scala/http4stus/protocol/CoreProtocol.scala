package http4stus.protocol

import http4stus.data.*

trait CoreProtocol[F[_]]:
  /** Look up an upload and return its current state. */
  def find(id: UploadId): F[Option[UploadState]]

  /** Receive a chunk of data from the given offset. */
  def receive(chunk: UploadChunk[F]): F[ReceiveResult]
