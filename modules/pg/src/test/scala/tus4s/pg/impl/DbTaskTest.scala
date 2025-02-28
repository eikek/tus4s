package tus4s.pg.impl

import cats.effect.*

import tus4s.pg.*
import tus4s.pg.impl.syntax.*

class DbTaskTest extends DbTestBase:

  test("basic connection"):
    randomDB.use { c =>
      IO {
        val rs = c.createStatement().executeQuery("SELECT 1")
        assert(rs.next())
        val n = rs.getInt(1)
        assertEquals(n, 1)
      }
    }

  test("create table, insert, select"):
    val t = for
      _ <- DbTask
        .prepare[IO](
          "CREATE TABLE testing (id bigint generated always as identity primary key, name varchar not null)"
        )
        .update

      _ <- DbTask
        .prepare[IO]("INSERT INTO testing (name) VALUES ('tamtaramtamtam')")
        .update

      n <- DbTask.prepare[IO]("SELECT * FROM testing").query.evaluate { rs =>
        rs.next()
        rs.getString("name")
      }
    yield n
    t.exec(randomDB).assertEquals("tamtaramtamtam")

  test("in tx with rollback"):
    val create =
      for
        _ <- DbTask
          .prepare[IO](
            "CREATE TABLE testing (id bigint generated always as identity primary key, name varchar not null)"
          )
          .update
        _ <- DbTask.fail(new Exception("peng"))
      yield ()

    val test = DbTask.prepare[IO]("SELECT * FROM testing").query.evaluate(_.next())

    randomDB.use { conn =>
      for
        _ <- create.inTx.run(conn).attempt
        r <- test.run(conn).attempt
        _ = assert(r.isLeft)
        _ = assert(
          r.swap.toOption.get.getMessage
            .startsWith("ERROR: relation \"testing\" does not exist")
        )
      yield ()
    }
