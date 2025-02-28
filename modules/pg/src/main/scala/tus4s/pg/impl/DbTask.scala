package tus4s.pg.impl

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

import cats.Applicative
import cats.MonadThrow
import cats.data.Kleisli
import cats.effect.*
import cats.syntax.all.*

import org.postgresql.PGConnection
import org.postgresql.largeobject.LargeObject
import org.postgresql.largeobject.LargeObjectManager
import tus4s.core.data.ByteRange

type DbTask[F[_], A] = Kleisli[F, Connection, A]

type DbTaskR[F[_], A] = Kleisli[Resource[F, _], Connection, A]

type DbTaskS[F[_], A] = Kleisli[fs2.Stream[F, _], Connection, A]

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

  def withAutoCommit[F[_]: Sync](flag: Boolean): DbTaskR[F, Unit] =
    resource { conn =>
      val old = conn.getAutoCommit()
      conn.setAutoCommit(flag)
      old
    }((conn, old) => conn.setAutoCommit(old)).void

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

  def loManager[F[_]: Sync]: DbTask[F, LargeObjectManager] =
    delay(_.unwrap(classOf[PGConnection]).getLargeObjectAPI())

  def createLargeObject[F[_]: Sync](lom: LargeObjectManager) =
    delay(_ => lom.createLO())

  def openLoRead[F[_]: Sync](
      lom: LargeObjectManager,
      oid: Long
  ): DbTaskR[F, LargeObject] =
    resource(_ => lom.open(oid, LargeObjectManager.READ, true))((_, lo) => lo.close())

  def openLoWrite[F[_]: Sync](
      lom: LargeObjectManager,
      oid: Long
  ): Resource[F, LargeObject] =
    Resource.make(Sync[F].blocking(lom.open(oid, LargeObjectManager.READWRITE, true)))(
      lo => Sync[F].blocking(lo.close())
    )

  def openLoWriteR[F[_]: Sync](
      lom: LargeObjectManager,
      oid: Long
  ): DbTaskR[F, LargeObject] =
    applyR(_ => openLoWrite(lom, oid))

  def seek[F[_]: Sync](lo: LargeObject, range: ByteRange): DbTask[F, Unit] =
    range match
      case ByteRange.All => pure(())
      case ByteRange.Chunk(offset, _) =>
        liftF(Sync[F].blocking(lo.seek64(offset.toBytes, LargeObject.SEEK_SET)))
