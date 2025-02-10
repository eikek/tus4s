package tus4s.pg

final case class PgConfig[F[_]](
  db: ConnectionResource[F],
  table: String
)
