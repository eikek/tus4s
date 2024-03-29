package http4stus.server

import http4stus.data.ByteSize
import org.http4s.Uri

final case class TusConfig[F[_]](
  baseUri: Option[Uri] = None,
  maxSize: Option[ByteSize] = None
)
