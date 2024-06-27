package tus4s.fs

import tus4s.data.ByteSize

final private case class TempChunk(id: String, length: ByteSize)
