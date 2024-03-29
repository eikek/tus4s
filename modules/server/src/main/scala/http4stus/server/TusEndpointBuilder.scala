package http4stus.server

import http4stus.protocol.*
import http4stus.Endpoint
import http4stus.data.ByteSize
import cats.effect.Sync
import org.http4s.Uri

final case class TusEndpointBuilder[F[_]: Sync](
    tus: TusProtocol[F],
    config: TusConfig[F] = TusConfig[F]()
):
  def build: Endpoint[F] = TusEndpoint(core, config)

  def modify(f: TusConfig[F] => TusConfig[F]): TusEndpointBuilder[F] =
    copy(config = f(this.config))

  def withBaseUri(uri: Uri): TusEndpointBuilder[F] =
    modify(_.copy(baseUri = Some(uri)))

  def withMaxSize(size: ByteSize): TusEndpointBuilder[F] =
    modify(_.copy(maxSize = Some(size)))

  def withTusProtocol(tus: TusProtocol[F]): TusEndpointBuilder[F] =
    copy(tus = tus)
