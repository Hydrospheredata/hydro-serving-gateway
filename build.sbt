import sbt._
import Keys._

name := "hydro-serving-gateway"

scalaVersion := "2.12.6"

lazy val currentAppVersion = sys.props.getOrElse("appVersion", "latest")

version := currentAppVersion

parallelExecution in Test := false
parallelExecution in IntegrationTest := false
fork in(Test, test) := true
fork in(IntegrationTest, test) := true
fork in(IntegrationTest, testOnly) := true
publishArtifact := false

organization := "io.hydrosphere.serving"

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Ypartial-unification",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps"
)

exportJars := false
resolvers += Resolver.bintrayRepo("findify", "maven")
resolvers += Resolver.bintrayRepo("hseeberger", "maven")
libraryDependencies ++= Dependencies.hydroServingGatewayDependencies

enablePlugins(sbtdocker.DockerPlugin)

dockerfile in docker := {
  val jarFile: File = sbt.Keys.`package`.in(Compile, packageBin).value
  val classpath = (dependencyClasspath in Compile).value
  val dockerFilesLocation = baseDirectory.value / "src/main/docker/"
  val jarTarget = s"/hydro-serving/app/app.jar"

  new Dockerfile {
    from("openjdk:8u151-jre-alpine")

    env("APP_PORT", "9090")

    add(dockerFilesLocation, "/hydro-serving/app/")
    // Add all files on the classpath
    add(classpath.files, "/hydro-serving/app/lib/")
    // Add the JAR file
    add(jarFile, jarTarget)

    volume("/model")
    cmd("/hydro-serving/app/start.sh")
  }
}

imageNames in docker := Seq(
  ImageName(s"hydrosphere/serving-gateway:${version.value}")
)