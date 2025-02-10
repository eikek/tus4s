package tus4s.pg.impl

import cats.effect.*
import cats.syntax.all.*
import java.sql.PreparedStatement
import java.sql.ResultSet
import tus4s.pg.ConnectionResource
import cats.data.Kleisli

object syntax {
  extension [F[_]: Sync](self: DbTaskR[F, PreparedStatement])
    def update: DbTask[F, Int] = self.mapF(_.use(DbTask.executeUpdate(_)))

    def updateWith(set: PreparedStatement => Unit): DbTask[F, Int] =
      self.mapF(_.use { ps =>
        set(ps)
        DbTask.executeUpdate(ps)
      })

    def query: DbTaskR[F, ResultSet] = self.flatMap(ps => DbTask.executeQuery(ps))

    def queryWith(set: PreparedStatement => Unit): DbTaskR[F, ResultSet] =
      for
        stmt <- self
        _ <- DbTask.applyR(_ => Resource.eval(Sync[F].delay(set(stmt))))
        rs <- DbTask.executeQuery(stmt)
      yield rs

  extension [F[_]: Sync](self: DbTaskR[F, ResultSet])
    def readOption[A](f: ResultSet => A): DbTask[F, Option[A]] =
      self.mapF(_.use(rs => Sync[F].blocking(if (rs.next()) Some(f(rs)) else None)))

  extension [F[_]: MonadCancelThrow, A](self: DbTaskR[F, A])
    def exec_(c: ConnectionResource[F]): F[A] =
      c.flatMap(self.run).use(_.pure[F])

    def evaluated: DbTask[F, A] =
      Kleisli(conn => self.run(conn).use(_.pure[F]))

    def evaluateT[B](f: A => DbTask[F, B]): DbTask[F, B] =
      Kleisli(conn => self.run(conn).use(a => f(a).run(conn)))

    def evaluate[B](f: A => B)(using Sync[F]): DbTask[F, B] =
      evaluateT(a => DbTask.lift(f(a)))

  extension [F[_], A](self: DbTask[F, A])
    def resource: DbTaskR[F, A] =
      self.mapF(a => Resource.eval(a))

    def inTx(using Sync[F]): DbTask[F, A] = DbTask.inTX(self)

  extension [F[_]: MonadCancelThrow, A](self: DbTask[F, A])
    def exec(c: ConnectionResource[F]): F[A] =
      c.useKleisli(self)

  extension (self: ResultSet)
    def stringColumn(name: String): Option[String] =
      Option(self.getString(name))

    def stringColumnRequire(name: String): String =
      stringColumn(name).getOrElse(sys.error(s"Column '$name' could not be retrieved"))

    def longColumn(name: String): Option[Long] =
      Option(self.getLong(name))

    def longColumnRequire(name: String): Long =
      longColumn(name).getOrElse(sys.error(s"Column '$name' could not be retrieved"))
}
