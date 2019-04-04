import sbt._
import Keys._

lazy val currentAppVersion = sys.props.getOrElse("appVersion", IO.read(file("version")).trim)

organization := "io.hydrosphere.serving"
organizationName := "hydrosphere"
organizationHomepage := Some(url("https://hydrosphere.io"))

name := "hydro-serving-gateway"
version := currentAppVersion

parallelExecution in Test := false
parallelExecution in IntegrationTest := false
fork in(Test, test) := true
fork in(IntegrationTest, test) := true
fork in(IntegrationTest, testOnly) := true
publishArtifact := false
exportJars := false

scalaVersion := "2.12.8"
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
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
mainClass in Compile := Some("io.hydrosphere.serving.gateway.Main")

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

enablePlugins(BuildInfoPlugin)
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.gitCurrentBranch, git.gitCurrentTags, git.gitHeadCommit)
buildInfoPackage := "io.hydrosphere.serving.gateway"
buildInfoOptions += BuildInfoOption.ToJson 