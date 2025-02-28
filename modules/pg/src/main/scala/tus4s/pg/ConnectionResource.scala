package tus4s.pg

import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

import cats.effect.*

type ConnectionResource[F[_]] = Resource[F, Connection]

object ConnectionResource:
  Class.forName("org.postgresql.Driver")

  def fromDataSource[F[_]: Sync](ds: DataSource): ConnectionResource[F] =
    Resource.make(Sync[F].blocking(ds.getConnection))(conn =>
      Sync[F].blocking(conn.close())
    )

  def simple[F[_]: Sync](
      url: String,
      user: Option[String] = None,
      password: Option[String] = None,
      options: Map[String, String] = Map.empty
  ): ConnectionResource[F] =
    val props = new java.util.Properties
    options.foreach { case (k, v) => props.setProperty(k, v) }
    user.foreach(u => props.setProperty("user", u))
    password.foreach(p => props.setProperty("password", p))
    Resource.make(Sync[F].blocking {
      DriverManager.getConnection(url, props)
    })(c => Sync[F].blocking(c.close()))
