package tus4s.pg

import cats.effect.*

import tus4s.core.TusProtocolTestBase
import tus4s.core.data.ByteSize

class PgTusProtocolTest extends TusProtocolTestBase with DbTestBase:

  val config = for
    dbName <- Resource.eval(newDbName)
    _ <- withDb(dbName)
    cr = makeConnectionResource(dbName)
    cfg = PgConfig(cr, "tus_files", None, ByteSize.kb(2))
  yield cfg

  def tusProtocol(maxSize: Option[ByteSize]) =
    config.evalMap(cfg => PgTusProtocol.create[IO](cfg.copy(maxSize = maxSize)))
