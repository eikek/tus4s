package tus4s.fs

import fs2.hashing.Hash

import tus4s.core.data.ByteSize

final private case class TempChunk(id: String, length: ByteSize, hash: Hash)
