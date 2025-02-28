package tus4s.pg

import java.sql.Connection

import cats.effect.*
import cats.syntax.all.*

import munit.CatsEffectSuite
import tus4s.pg.impl.DbTask
import tus4s.pg.impl.syntax.*
import wvlet.airframe.ulid.ULID

/** Establishes connections to the postgres database that is managed by sbts DbTestPlugin. */
trait DbTestBase extends CatsEffectSuite:
  private val dbPort = sys.env.getOrElse("POSTGRES_DB_PORT", "5432")
  private val connBase = ConnectionResource.simple[IO](
    url = s"jdbc:postgresql://localhost:$dbPort/postgres"
  )

  def withDb(name: String): Resource[IO, Unit] =
    val createTask = DbTask.prepare[IO](s"create database $name").update.void
    val dropTask = DbTask.prepare[IO](s"drop database $name").update.void
    val p1 = IO.println(s"Created db $name...")
    val p2 = IO.println(s"Dropped db $name...")
    Resource.make(connBase.use(createTask.run) >> p1)(_ =>
      connBase.use(dropTask.run) >> p2
    )

  val newDbName = IO(s"db_${ULID.newULIDString.takeRight(8).toLowerCase()}")

  def makeConnectionResource(dbname: String) =
    ConnectionResource.simple[IO](s"jdbc:postgresql://localhost:$dbPort/$dbname")

  private val createRandomDB: Resource[IO, String] =
    Resource.eval(newDbName).flatTap(withDb)

  val randomDB: ConnectionResource[IO] =
    createRandomDB.flatMap(db => makeConnectionResource(db))

  def dbOf(name: String): ConnectionResource[IO] =
    withDb(name).flatMap(_ => makeConnectionResource(name))

  def withRandomDB[A](t: DbTask[IO, A]): IO[A] =
    randomDB.use(t.run)

  val sameConnection: DbTask[IO, ConnectionResource[IO]] =
    DbTask(conn => IO(Resource.pure[IO, Connection](conn)))
