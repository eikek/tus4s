package http4stus.protocol

import http4stus.data.*

final case class TusConfig(
    extensions: Set[Extension] = Set.empty,
    maxSize: Option[ByteSize] = None
)
