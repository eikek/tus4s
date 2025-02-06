package tus4s.pg

final case class PgConfig(
  jdbcUrl: String,
  jdbcUser: Option[PgConfig.User]
)

object PgConfig:
  final case class User(username: String, password: String)
