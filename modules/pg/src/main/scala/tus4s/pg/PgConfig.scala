package tus4s.pg

import tus4s.core.data.ByteSize

final case class PgConfig[F[_]](
    db: ConnectionResource[F],
    table: String,
    maxSize: Option[ByteSize] = None,
    chunkSize: ByteSize = ByteSize.kb(64),
    enableConcat: Boolean = false
)
