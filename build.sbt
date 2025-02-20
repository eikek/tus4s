import Dependencies.V
import com.github.sbt.git.SbtGit.GitKeys._

addCommandAlias("ci", "Test/compile; lint; dbTests; publishLocal")
addCommandAlias(
  "lint",
  "scalafmtSbtCheck; scalafmtCheckAll; Compile/scalafix --check; Test/scalafix --check"
)
addCommandAlias("fix", "Compile/scalafix; Test/scalafix; scalafmtSbt; scalafmtAll")

val sharedSettings = Seq(
  organization := "com.github.eikek",
  scalaVersion := V.scala3,
  scalacOptions ++=
    Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-encoding",
      "UTF-8",
      "-language:higherKinds",
      "-Ykind-projector:underscores",
      "-source:future",
      "-Werror",
      "-indent",
      "-print-lines",
      "-Wunused:all"
    ),
  Compile / console / scalacOptions := Seq(),
  Test / console / scalacOptions := Seq(),
  licenses := Seq(
    "Apache-2.0" -> url("https://spdx.org/licenses/Apache-2.0.html")
  ),
  homepage := Some(url("https://github.com/eikek/tus4s")),
  versionScheme := Some("early-semver")
) ++ publishSettings

lazy val publishSettings = Seq(
  developers := List(
    Developer(
      id = "eikek",
      name = "Eike Kettner",
      url = url("https://github.com/eikek"),
      email = ""
    )
  ),
  Test / publishArtifact := false
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

val testSettings = Seq(
  libraryDependencies ++= Dependencies.munit.map(_ % Test),
  testFrameworks += TestFrameworks.MUnit
)

val scalafixSettings = Seq(
  semanticdbEnabled := true, // enable SemanticDB
  semanticdbVersion := scalafixSemanticdb.revision // use Scalafix compatible version
)

val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    version,
    scalaVersion,
    sbtVersion,
    gitHeadCommit,
    gitHeadCommitDate,
    gitUncommittedChanges,
    gitDescribedVersion
  ),
  buildInfoOptions ++= Seq(BuildInfoOption.ToMap, BuildInfoOption.BuildTime),
  buildInfoPackage := "com.github.eikek.tus"
)

val core = project
  .in(file("modules/core"))
  .settings(sharedSettings)
  .settings(testSettings)
  .settings(scalafixSettings)
  .settings(
    name := "tus4s-core",
    description := "Provides core data structures to use with tus",
    libraryDependencies ++= Dependencies.fs2Core ++ Dependencies.catsParse ++ Dependencies.ulid
  )

val fs = project
  .in(file("modules/fs"))
  .settings(sharedSettings)
  .settings(testSettings)
  .settings(scalafixSettings)
  .settings(
    name := "tus4s-fs",
    description := "Provides a backend for storing files in the local file system",
    libraryDependencies ++= Dependencies.fs2Io ++ Dependencies.catsEffect
  )
  .dependsOn(core % "compile->compile;test->test")

val pg = project
  .in(file("modules/pg"))
  .settings(sharedSettings)
  .settings(testSettings)
  .settings(scalafixSettings)
  .settings(
    name := "tus4s-pg",
    description := "Provides a backend for storing files in postgres",
    libraryDependencies ++= Dependencies.catsEffect ++ Dependencies.postgres
  )
  .dependsOn(core % "compile->compile;test->test")

val http4s = project
  .in(file("modules/http4s"))
  .settings(sharedSettings)
  .settings(testSettings)
  .settings(scalafixSettings)
  .settings(
    name := "tus4s-http4s",
    description := "Provides tus server routes",
    libraryDependencies ++=
      Dependencies.http4sCore ++
        Dependencies.http4sDsl,
    libraryDependencies ++= (Dependencies.http4sEmber ++
      Dependencies.http4sClient ++
      Dependencies.scribe).map(_ % Test),
    reStart / fullClasspath := (Test / fullClasspath).value,
    reStart / mainClass := Some("tus4s.http4s.server.ServerTest")
  )
  .dependsOn(core, fs % "test->test", pg % "test->test")

val updateReadme = inputKey[Unit]("Update readme")
lazy val readme = project
  .in(file("modules/readme"))
  .enablePlugins(MdocPlugin)
  .settings(sharedSettings)
  .settings(scalafixSettings)
  .settings(noPublish)
  .settings(
    name := "tus4s-readme",
    libraryDependencies ++= Dependencies.http4sEmber,
    mdocIn := (LocalRootProject / baseDirectory).value / "docs" / "readme.md",
    mdocOut := (LocalRootProject / baseDirectory).value / "README.md",
    fork := true,
    updateReadme := {
      mdoc.evaluated
      ()
    }
  )
  .dependsOn(core, fs, http4s)

val root = project
  .in(file("."))
  .disablePlugins(RevolverPlugin)
  .settings(sharedSettings)
  .settings(noPublish)
  .settings(
    name := "tus4s-root"
  )
  .aggregate(core, fs, pg, http4s)
