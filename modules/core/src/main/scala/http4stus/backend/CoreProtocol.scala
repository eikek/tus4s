package http4stus

import fs2.Stream
import http4stus.data.*

trait CoreProtocol[F[_]]:
  /** Look up an upload and return its current state. */
  def find(id: UploadId): F[Option[UploadState]]

  /** Receive a chunk of data from the given offset. */
  def receive(id: UploadId, offset: ByteSize, data: Stream[F, Byte]): F[ReceiveResult]
