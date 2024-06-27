package tus4s.protocol

import tus4s.data.*

final case class TusConfig(
    extensions: Set[Extension] = Set.empty,
    maxSize: Option[ByteSize] = None
):

  def addExtension(ext: Extension): TusConfig =
    copy(extensions = extensions + ext)

  def addExtensions(pp: (Boolean, Extension)*): TusConfig =
    pp.foldLeft(this) { case (cfg, (cond, ext)) =>
      if (cond) cfg.addExtension(ext) else this
    }
