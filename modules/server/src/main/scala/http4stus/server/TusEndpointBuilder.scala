package http4stus

import http4stus.data.Extension
import http4stus.protocol.*

final class TusEndpointBuilder[F[_]](
    core: CoreProtocol[F],
    extensions: Set[Extension] = Set.empty
):
  println(core)

  def build: Endpoint[F] = ???

  def withCoreProtocol(coreProtocol: CoreProtocol[F]): TusEndpointBuilder[F] =
    TusEndpointBuilder[F](coreProtocol, extensions)

  def withCreation(creation: CreationExtension[F]): TusEndpointBuilder[F] =
    ???
