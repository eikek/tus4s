package tus4s.http4s.server

import cats.ApplicativeThrow
import fs2.{Pull, Stream}

import tus4s.core.data.ByteSize

object LimitStream:

  def apply[F[_]: ApplicativeThrow](s: Stream[F, Byte], max: ByteSize): Stream[F, Byte] =
    s.pull
      .take(max.toBytes)
      .flatMap:
        case Some(s) =>
          s.pull.uncons.flatMap:
            case Some(_) =>
              Pull.raiseError(
                TusDecodeFailure.MaxSizeExceeded(max, max)
              )
            case None => Pull.done
        case _ => Pull.done
      .stream
