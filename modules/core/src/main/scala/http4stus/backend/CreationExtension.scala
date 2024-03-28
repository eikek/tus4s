package http4stus.protocol

import http4stus.data.UploadId
import http4stus.protocol.creation.headers.UploadMetadata

trait CreationExtension[F[_]]:
  def create(meta: Option[UploadMetadata]): F[UploadId]
