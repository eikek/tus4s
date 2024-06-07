# http4s-tus

This project provides routes for http4s to enable file uploads via the
[tus protocol](https://tus.io/protocols/resumable-upload).

It is comprised of the following modules:

- *core* depends on `http4s-core` (only) and provides the basic data
  structures for supporting the tus protocol
- *server* depends on `http4s-dsl` to implement the tus protocol as a
  `HttpRoutes` value that you can mount in your endpoint hierarchy

## Usage

First an implementation of `TusProtocol` is required, there is one
provided using a directory to store uploads.

```scala mdoc
import cats.effect.*
import fs2.io.file.Path

import http4stus.data.ByteSize
import http4stus.fs.FsTusProtocol
import http4stus.protocol.TusProtocol

val tusBackend: IO[TusProtocol[IO]] = FsTusProtocol.create[IO](Path("/tmp/tus-test"), Some(ByteSize.mb(500)))
```

With such a backend, the endpoint can be created:

```scala mdoc
import http4stus.server.{Retrieve, TusEndpointBuilder}
import org.http4s.implicits.*

def tusEndpoint(backend: TusProtocol[IO]) =
  TusEndpointBuilder[IO](backend)
    .withBaseUri(uri"/files")
    .withRetrieve(Retrieve.simpleGet[IO])
    .build
    .routes
```

The optional `withRetrieve` allows to inject code to also get a file
back.

Finally, putting all together in a server:

```scala mdoc
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.server.Router
import org.http4s.ember.server.EmberServerBuilder

for
  backend <- tusBackend
  app = Router("files" -> tusEndpoint(backend)).orNotFound
  _ <- EmberServerBuilder
    .default[IO]
    .withHost(host"0.0.0.0")
    .withPort(port"8888")
    .withHttpApp(app)
    .build
    .useForever
yield ExitCode.Success
```
