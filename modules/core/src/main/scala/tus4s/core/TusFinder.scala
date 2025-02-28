package tus4s.core

import tus4s.core.data.*

trait TusFinder[F[_]]:

  /** Look up an upload and return its current state. */
  def find(id: UploadId, range: ByteRange): F[Option[FileResult[F]]]

object TusFinder:

  def apply[F[_]](f: (UploadId, ByteRange) => F[Option[FileResult[F]]]): TusFinder[F] =
    new TusFinder[F] {
      def find(id: UploadId, range: ByteRange): F[Option[FileResult[F]]] = f(id, range)
    }
