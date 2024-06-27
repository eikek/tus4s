package tus4s.fs

import cats.MonadThrow
import cats.syntax.all.*
import fs2.{Pipe, Stream}

import tus4s.data.*
import tus4s.protocol.headers.*

object StateCodec:

  def toBytes[F[_]](state: UploadState): Stream[F, Byte] =
    Stream
      .emits(
        List(
          state.offset.toBytes.toString,
          state.length.map(_.toBytes.toString).getOrElse(""),
          Option
            .when(!state.meta.isEmpty)(state.meta)
            .map(md => UploadMetadata(md).encoded)
            .getOrElse(""),
          state.concatType.map(UploadConcat.apply).map(_.render).getOrElse("")
        )
      )
      .intersperse("\n")
      .through(fs2.text.utf8.encode[F])

  def fromLines[F[_]: MonadThrow](id: UploadId): Pipe[F, String, UploadState] =
    val init: Either[String, UploadState] = Right(UploadState(id))
    _.filter(!_.startsWith("#")).zipWithIndex
      .fold(init):
        // Offset
        case (Right(state), (line, 0)) =>
          line.toByteSize.map(sz => state.copy(offset = sz))

        // Length
        case (Right(state), (line, 1)) =>
          if (line.isEmpty) Right(state)
          else line.toByteSize.map(sz => state.copy(length = sz.some))

        // Metadata
        case (Right(state), (line, 2)) =>
          if (line.isEmpty) Right(state)
          else
            UploadMetadata
              .parse(line)
              .leftMap(_.message)
              .map(md => state.copy(meta = md.decoded))

        // ConcatType
        case (Right(state), (line, 3)) =>
          if (line.isEmpty) Right(state)
          else
            UploadConcat
              .parse(line)
              .leftMap(_.message)
              .map(h => state.copy(concatType = h.concatType.some))

        // ignore other lines
        case (state, _) => state
      .map(_.leftMap(new Exception(_)))
      .rethrow

  extension (self: String)
    def toByteSize: Either[String, ByteSize] =
      self.toLongOption
        .toRight(s"Invalid number at line 0: $self")
        .map(n => ByteSize.bytes(n))
