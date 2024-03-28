package http4stus.server

import http4stus.protocol.creation.*
import http4stus.data.Extension

final case class ExtensionConfig[F[_]](
    creation: Option[CreationExtension[F]] = None,
    creationWithUpload: Option[CreationWithUploadExtension[F]] = None
):
  def withCreation(c: CreationExtension[F]): ExtensionConfig[F] =
    copy(creation = Some(c))

  def withCreationWithUpload(c: CreationWithUploadExtension[F]): ExtensionConfig[F] =
    copy(creationWithUpload = Some(c))

  lazy val all: Set[Extension] =
    Set(
      creation.map(_ => Extension.Creation).toSet,
      creationWithUpload.toSet.flatMap(_ =>
        Set(Extension.Creation, Extension.CreationWithUpload)
      )
    ).flatten
