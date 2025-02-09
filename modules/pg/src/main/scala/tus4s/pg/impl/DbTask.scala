package tus4s.pg.impl

import cats.data.Kleisli
import java.sql.Connection
import cats.Applicative
import cats.effect.*
import cats.syntax.all.*
import cats.MonadThrow
import java.sql.PreparedStatement
import java.sql.ResultSet

type DbTask[F[_], A] = Kleisli[F, Connection, A]

type DbTaskR[F[_], A] = Kleisli[Resource[F, _], Connection, A]

object DbTask:

  def apply[F[_], A](f: Connection => F[A]): DbTask[F, A] =
    Kleisli(f)

  def applyR[F[_], A](f: Connection => Resource[F, A]): DbTaskR[F, A] =
    Kleisli(f)

  def pure[F[_]: Applicative, A](a: A): DbTask[F, A] =
    Kleisli.pure(a)

  def delay[F[_]: Sync, A](f: Connection => A): DbTask[F, A] =
    Kleisli(conn => Sync[F].blocking(f(conn)))

  def lift[F[_]: Sync, A](a: => A): DbTask[F, A] =
    delay(_ => a)

  def liftF[F[_], A](a: F[A]): DbTask[F, A] =
    Kleisli.liftF(a)


  def fail[F[_]: MonadThrow, A](ex: Throwable): DbTask[F, A] =
    DbTask(_ => MonadThrow[F].raiseError(ex))

  def resource[F[_]: Sync, A](
      acquire: Connection => A
  )(release: (Connection, A) => Unit): DbTaskR[F, A] =
    DbTask(conn =>
      Resource.make(Sync[F].blocking(acquire(conn)))(a =>
        Sync[F].blocking(release(conn, a))
      )
    )

  def resourceF[F[_]: Sync, A](
      acquire: Connection => F[A]
  )(release: (Connection, A) => F[Unit]): DbTaskR[F, A] =
    DbTask(conn => Resource.make(acquire(conn))(a => release(conn, a)))

  def commit[F[_]: Sync]: DbTask[F, Unit] =
    delay(_.commit())

  def rollback[F[_]: Sync]: DbTask[F, Unit] =
    delay(_.rollback())

  def setAutoCommit[F[_]: Sync](flag: Boolean): DbTask[F, Boolean] =
    delay { conn =>
      val old = conn.getAutoCommit()
      conn.setAutoCommit(flag)
      old
    }

  private def makeTX[F[_]: Sync]: DbTaskR[F, Unit] =
    DbTask { conn =>
      val ac = Resource.make(setAutoCommit(false).run(conn))(flag =>
        setAutoCommit(flag).run(conn).void
      )
      val comm = Resource.onFinalizeCase {
        case Resource.ExitCase.Errored(ex) =>
          Sync[F].blocking {
            ex.printStackTrace()
            conn.rollback()
          }

        case Resource.ExitCase.Canceled =>
          Sync[F].blocking(conn.rollback())

        case Resource.ExitCase.Succeeded =>
          Sync[F].blocking(conn.commit())
      }
      ac >> comm
    }

  def withResource[F[_]: Sync, A, B](
      dba: DbTaskR[F, A]
  )(f: A => DbTask[F, B]): DbTask[F, B] =
    DbTask { conn =>
      dba.mapF(_.use(a => f(a).run(conn))).run(conn)
    }

  def inTX[F[_]: Sync, A](dbr: DbTask[F, A]): DbTask[F, A] =
    withResource(makeTX[F])(_ => dbr)

  def prepare[F[_]: Sync](sql: String): DbTaskR[F, PreparedStatement] =
    resource(_.prepareStatement(sql))((_, p) => p.close())

  def executeUpdate[F[_]: Sync](ps: PreparedStatement): F[Int] =
    Sync[F].blocking(ps.executeUpdate())

  def executeQuery[F[_]: Sync](ps: PreparedStatement): DbTaskR[F, ResultSet] =
    resource(_ => ps.executeQuery())((_, rs) => rs.close())
