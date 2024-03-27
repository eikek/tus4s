package http4stus

import org.http4s.*

trait Endpoint[F[_]]:
  def routes: HttpRoutes[F]
  def app: HttpApp[F]
