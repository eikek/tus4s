#!/usr/bin/env -S scala-cli shebang
//> using dep io.tus.java.client:tus-java-client:0.5.0
// see https://github.com/tus/tus-java-client

import java.io.File
import java.net.URL

import io.tus.java.client.*

val client = new TusClient()
client.setUploadCreationURL(new URL("http://localhost:8888/files"))
client.enableResuming(new TusURLMemoryStore())

val file = new File(args.head)
val upload = new TusUpload(file)

val executor = new TusExecutor {
  override protected def makeAttempt(): Unit = {
    val uploader = client.resumeOrCreateUpload(upload)
    uploader.setChunkSize(256)
    var going = true
    while (going) {
      val total = upload.getSize()
      val current = uploader.getOffset()
      val progress = current.toDouble / total * 100
      print(f"\rUploading $progress%6.2f")
      going = uploader.uploadChunk() != -1
    }
    println("")
  }
}
println(s"Trying uploading ${args.head}")
executor.makeAttempts()
