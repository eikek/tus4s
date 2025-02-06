package tus4s.pg.impl

import cats.data.Kleisli
import java.sql.Connection
import cats.Applicative
import cats.effect.*
import cats.syntax.all.*
import cats.MonadThrow

type DbTask[F[_], A] = Kleisli[F, Connection, A]

object DbTask:

  def apply[F[_], A](f: Connection => F[A]): DbTask[F, A] =
    Kleisli(f)

  def pure[F[_]: Applicative, A](a: A): DbTask[F, A] =
    Kleisli.pure(a)

  def delay[F[_]: Sync, A](f: Connection => A): DbTask[F, A] =
    Kleisli(conn => Sync[F].blocking(f(conn)))

  def fail[F[_]: MonadThrow, A](ex: Throwable): DbTask[F, A] =
    DbTask(_ => MonadThrow[F].raiseError(ex))

  def resource[F[_]: Sync, A](
      acquire: Connection => A
  )(release: A => Unit): DbTask[Resource[F, *], A] =
    DbTask(conn =>
      Resource.make(Sync[F].blocking(acquire(conn)))(a => Sync[F].blocking(release(a)))
    )

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

  private def makeTX[F[_]: Sync]: DbTask[Resource[F, *], Unit] =
    DbTask { conn =>

      val ac = Resource.make(setAutoCommit(false).run(conn))(flag => setAutoCommit(flag).run(conn).void)

      val comm = Resource.onFinalizeCase {
        case Resource.ExitCase.Errored(ex) =>
          ex.printStackTrace()
          conn.rollback()
      }
      ac.void
    }
