package tus4s.core

import cats.Applicative
import cats.effect.*
import fs2.Chunk
import fs2.hashing.{Hash as FHash, *}

import tus4s.core.data.ChecksumAlgorithm

object HashSupport:

  private def noHasher[F[_]: Applicative] = new Hasher[F] {
    def hash: F[FHash] = Applicative[F].pure(FHash(Chunk.empty))
    protected def unsafeHash(): FHash = FHash(Chunk.empty)
    protected def unsafeUpdate(chunk: Chunk[Byte]): Unit = ()
    def update(bytes: Chunk[Byte]): F[Unit] = Applicative[F].pure(())
  }

  def hasher[F[_]: Sync](alg: Option[ChecksumAlgorithm]): Resource[F, Hasher[F]] =
    alg.map(hasher(_)).getOrElse(Resource.pure(noHasher))

  def hasher[F[_]: Sync](alg: ChecksumAlgorithm): Resource[F, Hasher[F]] =
    alg match
      case ChecksumAlgorithm.Md5    => Hashing.forSync.hasher(HashAlgorithm.MD5)
      case ChecksumAlgorithm.Sha1   => Hashing.forSync.hasher(HashAlgorithm.SHA1)
      case ChecksumAlgorithm.Sha256 => Hashing.forSync.hasher(HashAlgorithm.SHA256)
      case ChecksumAlgorithm.Sha512 => Hashing.forSync.hasher(HashAlgorithm.SHA512)
