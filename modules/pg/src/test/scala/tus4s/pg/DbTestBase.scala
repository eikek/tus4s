package tus4s.pg

import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite
import wvlet.airframe.ulid.ULID
import tus4s.pg.impl.DbTask
import tus4s.pg.impl.syntax.*

/** Establishes connections to the postgres database that is managed by sbts DbTestPlugin. */
trait DbTestBase extends CatsEffectSuite:
  private val dbPort = sys.env.getOrElse("POSTGRES_DB_PORT", "5432")
  private val connBase = ConnectionResource.simple[IO](
    url = s"jdbc:postgresql://localhost:$dbPort/postgres"
  )

  private def withDb(name: String): Resource[IO, Unit] =
    val createTask = DbTask.prepare[IO](s"create database $name").update.void
    val dropTask = DbTask.prepare[IO](s"drop database $name").update.void
    val p1 = IO.println(s"Created db $name...")
    val p2 = IO.println(s"Dropped db $name...")
    Resource.make(connBase.use(createTask.run) >> p1)(_ =>
      connBase.use(dropTask.run) >> p2
    )

  private val createRandomDB: Resource[IO, String] =
    for
      c <- connBase
      name <- Resource
        .eval(IO(ULID.newULIDString.takeRight(8).toLowerCase()))
        .map(e => s"db_$e")
      _ <- withDb(name)
    yield name

  val randomDB: ConnectionResource[IO] = createRandomDB.flatMap(db =>
    ConnectionResource.simple[IO](s"jdbc:postgresql://localhost:$dbPort/$db")
  )
