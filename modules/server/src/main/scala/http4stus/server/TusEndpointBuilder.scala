package http4stus.server

import cats.effect.Sync

import http4stus.Endpoint
import http4stus.protocol.*
import org.http4s.Uri

final case class TusEndpointBuilder[F[_]: Sync](
    tus: TusProtocol[F],
    baseUri: Option[Uri] = None,
    allowRetrieve: Boolean = false
):
  def build: Endpoint[F] = TusEndpoint(tus, allowRetrieve, baseUri)

  def withBaseUri(uri: Uri): TusEndpointBuilder[F] =
    copy(baseUri = Some(uri))

  def withAllowRetrieve: TusEndpointBuilder[F] =
    copy(allowRetrieve = true)

  def withTusProtocol(tus: TusProtocol[F]): TusEndpointBuilder[F] =
    copy(tus = tus)
