package tus4s.http4s.server

import cats.effect.*

import tus4s.core.TusProtocol
import tus4s.core.data.ByteSize
import tus4s.pg.DbTestBase
import tus4s.pg.PgConfig
import tus4s.pg.PgTusProtocol

class PgTusEndpointSpec
    extends TusEndpointSuite(
      PgTusEndpointSpec.createPgProtocol.map(p =>
        TusEndpointBuilder(p).withRetrieve(Retrieve.simpleGet[IO]).build
      )
    )

object PgTusEndpointSpec extends DbTestBase:

  val config: Resource[IO, PgConfig[IO]] = for
    dbName <- Resource.eval(newDbName)
    _ <- withDb(dbName)
    cr = makeConnectionResource(dbName)
    cfg = PgConfig(cr, "tus_files", Some(ByteSize.kb(70)))
  yield cfg

  val createPgProtocol: Resource[IO, TusProtocol[IO]] =
    config.evalMap(cfg => PgTusProtocol.create[IO](cfg))
