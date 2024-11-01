package tus4s.http4s.server

import cats.effect.Sync

import org.http4s.Uri
import tus4s.core.*
import tus4s.http4s.Endpoint

final case class TusEndpointBuilder[F[_]: Sync](
    tus: TusProtocol[F],
    baseUri: Option[Uri] = None,
    retrieve: Option[Retrieve[F]] = None
):
  def build: Endpoint[F] = TusEndpoint(tus, retrieve, baseUri)

  def withBaseUri(uri: Uri): TusEndpointBuilder[F] =
    copy(baseUri = Some(uri))

  def withRetrieve(r: Retrieve[F]): TusEndpointBuilder[F] =
    copy(retrieve = Some(r))

  def withTusProtocol(tus: TusProtocol[F]): TusEndpointBuilder[F] =
    copy(tus = tus)
