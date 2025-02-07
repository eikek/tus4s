package tus4s.pg

import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite
import wvlet.airframe.ulid.ULID
import tus4s.pg.impl.DbTask

class DbTaskTest extends CatsEffectSuite:

  val connBase = ConnectionResource.simple[IO](
    url = "jdbc:postgresql://localhost:5432/postgres"
  )

  def withDb(name: String) =
    val createTask = DbTask.prepare[IO](s"create database $name").mapF(_.use(DbTask.executeUpdate(_))).void
    val dropTask = DbTask.prepare[IO](s"drop database $name").mapF(_.use(DbTask.executeUpdate(_))).void
    val p1 = IO.println(s"Created db $name...")
    val p2 = IO.println(s"Dropped db $name...")
    Resource.make(connBase.use(createTask.run) >> p1)(_ => connBase.use(dropTask.run) >> p2)

  val makeDb =
    for
      c <- connBase
      name <- Resource.eval(IO(ULID.newULIDString.takeRight(8).toLowerCase())).map(e => s"db_$e")
      _ <- withDb(name)
    yield name

  val randomDB = makeDb.flatMap(db => ConnectionResource.simple[IO](s"jdbc:postgresql://localhost:5432/$db"))

  test("in tx"):
    randomDB.use { c =>
      val rs = c.createStatement().executeQuery("SELECT 1")
      assert(rs.next())
      val n = rs.getInt(1)
      assertEquals(n, 1)
      Thread.sleep(3000)
      IO.unit
    }
