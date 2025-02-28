package tus4s.pg

import cats.effect.IO

import tus4s.http4s.server.ServerTest

object PgServerTest extends ServerTest:

  val tusBackend = PgTusProtocol.create[IO](
    PgConfig(
      ConnectionResource.simple("jdbc:postgresql://localhost:5432/tus_test"),
      "tus_files"
    )
  )
