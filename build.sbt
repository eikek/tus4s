import Dependencies.V
import com.github.sbt.git.SbtGit.GitKeys._

addCommandAlias("ci", "Test/compile; lint; test; publishLocal")
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
  homepage := Some(url("https://github.com/eikek/http4s-tus")),
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
    name := "http4s-tus-core",
    description := "Provides data structures to use with tus and http4s",
    libraryDependencies ++= Dependencies.http4sCore
  )

val server = project
  .in(file("modules/server"))
  .settings(sharedSettings)
  .settings(testSettings)
  .settings(scalafixSettings)
  .settings(
    name := "http4s-tus-server",
    description := "Provides tus server routes",
    libraryDependencies ++=
      Dependencies.http4sCore ++
        Dependencies.http4sDsl,
    libraryDependencies ++= (Dependencies.http4sEmber ++
      Dependencies.http4sClient ++
      Dependencies.scribe).map(_ % Test),
    reStart / fullClasspath := (Test / fullClasspath).value,
    reStart / mainClass := Some("http4stus.server.ServerTest")
  )
  .dependsOn(core)

val updateReadme = inputKey[Unit]("Update readme")
lazy val readme = project
  .in(file("modules/readme"))
  .enablePlugins(MdocPlugin)
  .settings(sharedSettings)
  .settings(scalafixSettings)
  .settings(noPublish)
  .settings(
    name := "http4s-tus-readme",
    libraryDependencies ++= Dependencies.http4sEmber,
    mdocIn := (LocalRootProject / baseDirectory).value / "docs" / "readme.md",
    mdocOut := (LocalRootProject / baseDirectory).value / "README.md",
    fork := true,
    updateReadme := {
      mdoc.evaluated
      ()
    }
  )
  .dependsOn(core, server)

val root = project
  .in(file("."))
  .disablePlugins(RevolverPlugin)
  .settings(sharedSettings)
  .settings(noPublish)
  .settings(
    name := "http4s-tus-root"
  )
  .aggregate(core, server)
