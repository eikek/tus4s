# tus4s

This project provides routes for http4s to enable file uploads via the
[tus protocol](https://tus.io/protocols/resumable-upload).

It is comprised of the following modules:

- *core* provides some core structures for supporting the tus
  protocol
- *fs* provides a backend for storing files on the local file system
- *http4s* depends on `http4s-dsl` to implement the tus protocol as
  server `HttpRoutes` value that you can mount in your endpoint
  hierarchy

## Usage

First an implementation of `TusProtocol` is required, there is one
provided using a directory to store uploads.

```scala mdoc
import cats.effect.*
import fs2.io.file.Path

import tus4s.core.data.ByteSize
import tus4s.fs.FsTusProtocol
import tus4s.core.TusProtocol

val tusBackend: IO[TusProtocol[IO]] = FsTusProtocol.create[IO](Path("/tmp/tus-test"), Some(ByteSize.mb(500)))
```

With such a backend, the endpoint can be created:

```scala mdoc
import tus4s.http4s.server.{Retrieve, TusEndpointBuilder}
import org.http4s.implicits.*

def tusEndpoint(backend: TusProtocol[IO]) =
  TusEndpointBuilder[IO](backend)
    .withBaseUri(uri"/files")
    .withRetrieve(Retrieve.simpleGet[IO])
    .build
    .routes
```

The optional `withRetrieve` allows to inject code to also get a file
back on the `GET <base-uri>` route. The configuration for the tus
protocol (i.e. which extensions are enabled) is a feature of the
backend (here `FsTusBackend`, look at it's constructor for what is
available).

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
