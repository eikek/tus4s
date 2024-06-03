package http4stus

import http4stus.protocol.TusConfig
import org.http4s.*

trait Endpoint[F[_]]:
  def config: TusConfig
  def routes: HttpRoutes[F]
  def app: HttpApp[F]
