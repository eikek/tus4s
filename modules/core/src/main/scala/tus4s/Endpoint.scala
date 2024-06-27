package tus4s

import tus4s.protocol.TusConfig
import org.http4s.*

trait Endpoint[F[_]]:
  def config: TusConfig
  def routes: HttpRoutes[F]
  def app: HttpApp[F]
