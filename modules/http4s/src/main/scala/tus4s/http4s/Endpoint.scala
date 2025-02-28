package tus4s.http4s

import org.http4s.*
import tus4s.core.TusConfig

trait Endpoint[F[_]]:
  def config: TusConfig
  def routes: HttpRoutes[F]
  def app: HttpApp[F]
