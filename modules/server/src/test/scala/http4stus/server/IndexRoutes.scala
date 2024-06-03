package http4stus.server

import cats.effect.*

import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpRoutes, MediaType}

object IndexRoutes extends Http4sDsl[IO]:

  def routes = HttpRoutes.of[IO] {
    case GET -> Root =>
      Ok(indexHtml, `Content-Type`(MediaType.text.html))

    case GET -> Root / "tusc.js" =>
      Ok(tuscjs, `Content-Type`(MediaType.application.javascript))
  }

  def indexHtml = """
  <!DOCTYPE html>
<html>
    <head>
        <title>Tus Client</title>
        <script src="https://cdn.jsdelivr.net/npm/tus-js-client@latest/dist/tus.min.js"></script>
    </head>
    <body>
        <h1>Tus Client to localhost:8888</h1>

        <div>
            <input id="file" type="file" name="file">
        </div>
    </body>
    <script src="/tusc.js"></script>
</html>
  """

  def tuscjs = """
  var input = document.getElementById("file")
input.addEventListener('change', function (e) {
  // Get the selected file from the input element
  var file = e.target.files[0]

  // Create a new tus upload
  var upload = new tus.Upload(file, {
    endpoint: 'http://localhost:8888/files',
    retryDelays: [0, 3000, 5000, 10000, 20000],
    metadata: {
      filename: file.name,
      filetype: file.type,
    },
    onError: function (error) {
      console.log('Failed because: ' + error)
    },
    onProgress: function (bytesUploaded, bytesTotal) {
      var percentage = ((bytesUploaded / bytesTotal) * 100).toFixed(2)
      console.log(bytesUploaded, bytesTotal, percentage + '%')
    },
    onSuccess: function () {
      console.log('Download %s from %s', upload.file.name, upload.url)
    },
  })

  // Check if there are any previous uploads to continue.
  upload.findPreviousUploads().then(function (previousUploads) {
    // Found previous uploads so we select the first one.
    if (previousUploads.length) {
      upload.resumeFromPreviousUpload(previousUploads[0])
    }

    // Start the upload
    upload.start()
  })
})

"""
