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

    def query: DbTaskR[F, ResultSet] = self.flatMap(ps => DbTask.executeQuery(ps))

    def queryWith(set: PreparedStatement => Unit): DbTaskR[F, ResultSet] =
      for
        stmt <- self
        _ <- DbTask.applyR(_ => Resource.eval(Sync[F].delay(set(stmt))))
        rs <- DbTask.executeQuery(stmt)
      yield rs

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

}
