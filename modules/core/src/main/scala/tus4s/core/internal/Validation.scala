package tus4s.core.internal

import cats.syntax.all.*

import tus4s.core.data.*

private[tus4s] object Validation:

  def validateReceive[F[_]](
      state: UploadState,
      req: UploadRequest[F],
      maxSize: Option[ByteSize]
  ): Option[ReceiveResult] = {
    def maxSizeCheck =
      validateMaxSize(state.offset, req, maxSize)(ReceiveResult.UploadTooLarge.apply)

    def stateLenCheck =
      for
        stateLen <- state.length
        reqLen <- req.uploadLength
        if stateLen != reqLen
      yield ReceiveResult.UploadLengthMismatch(stateLen, reqLen)

    maxSizeCheck.orElse(stateLenCheck).orElse {
      if (state.offset != req.offset)
        ReceiveResult.OffsetMismatch(state.offset).some
      else if (state.isDone) ReceiveResult.UploadDone.some
      else if (state.isFinal) ReceiveResult.UploadIsFinal.some
      else None
    }
  }

  def validateCreate[F[_]](
      req: UploadRequest[F],
      maxSize: Option[ByteSize]
  ): Option[CreationResult] =
    validateMaxSize(ByteSize.zero, req, maxSize)(CreationResult.UploadTooLarge.apply)

  def validateMaxSize[F[_], A](
      offset: ByteSize,
      req: UploadRequest[F],
      maxSize: Option[ByteSize]
  )(f: (ByteSize, ByteSize) => A): Option[A] =
    None
    maxSize.flatMap { ms =>
      req.uploadLength
        .filter(_ > ms)
        .map(us => f(ms, us))
        .orElse(
          req.contentLength
            .filter(cs => (cs + offset) > ms)
            .map(cs => f(ms, cs + offset))
        )
    }
