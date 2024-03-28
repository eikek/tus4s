package http4stus.server

import http4stus.protocol.*
import http4stus.protocol.creation.CreationExtension
import http4stus.Endpoint

final case class TusEndpointBuilder[F[_]](
    core: CoreProtocol[F],
    extensionConfig: ExtensionConfig[F]
):
  println(core)

  def build: Endpoint[F] = ???

  def withCoreProtocol(coreProtocol: CoreProtocol[F]): TusEndpointBuilder[F] =
    copy(core = coreProtocol)

  def withCreation(creation: CreationExtension[F]): TusEndpointBuilder[F] =
    copy(extensionConfig = extensionConfig.withCreation(creation))

object TusEndpointBuilder:
  def apply[F[_]](core: CoreProtocol[F]): TusEndpointBuilder[F] =
    TusEndpointBuilder[F](core, ExtensionConfig[F]())
