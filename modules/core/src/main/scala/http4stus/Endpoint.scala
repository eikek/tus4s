package http4stus

import org.http4s.Request

final class Endpoint[F[_]]:

  def head(req: Request[F]) = ???
