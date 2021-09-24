import sbt._
import Keys._

name := "serving-gateway"
version := sys.props.getOrElse("appVersion", IO.read(file("version")).trim)

//githubOwner := "Hydrospheredata"
//githubRepository := "hydro-serving-protos"

organization := "io.hydrosphere.serving"
organizationName := "hydrosphere"
organizationHomepage := Some(url("https://hydrosphere.io"))

parallelExecution in Test := false
parallelExecution in IntegrationTest := false
fork in(Test, test) := true
fork in(IntegrationTest, test) := true
fork in(IntegrationTest, testOnly) := true
logBuffered in Test := false  // http://www.scalatest.org/user_guide/using_scalatest_with_sbt
publishArtifact := false
exportJars := false

scalaVersion := "2.13.2"
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps"
)
mainClass in Compile := Some("io.hydrosphere.serving.gateway.Main")

resolvers += Resolver.sonatypeRepo("public")
resolvers += Resolver.sonatypeRepo("releases")
// resolvers += Resolver.bintrayRepo("findify", "maven")
// resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= Dependencies.all

enablePlugins(sbtdocker.DockerPlugin)

daemonUserUid in Docker := None
daemonUser in Docker    := "daemon"

dockerfile in docker := {
  val jarFile: File = sbt.Keys.`package`.in(Compile, packageBin).value
  val classpath = (dependencyClasspath in Compile).value
  val dockerFilesLocation = baseDirectory.value / "src/main/docker/"
  val jarTarget = "app.jar"

  new Dockerfile {
    from("openjdk:8u212-jre-alpine3.9")

    label("maintainer", "support@hydrosphere.io")

    env("APP_PORT", "9090")

    run("apk", "update")
    run("apk", "add", "--no-cache", "freetype>=2.9.1-r3", "krb5-libs>=1.15.5-r1", "libbz2>=1.0.6-r7", 
        "libcom_err>=1.44.5-r2", "libcrypto1.1>=1.1.1k-r0", "libjpeg-turbo>=1.5.3-r6", "libssl1.1>=1.1.1k-r0", 
        "libtasn1>=4.14", "libx11>=1.6.12-r0", "musl>=1.1.20-r6", "openjdk8-jre>=8.272.10-r0", 
        "openjdk8-jre-base>=8.272.10-r0", "openjdk8-jre-lib>=8.272.10-r0", "sqlite-libs>=3.28.0-r3")
    run("rm", "-rf", "/var/cache/apk/*")

    workDir("/hydro-serving/app/")

    copy(dockerFilesLocation, "./", "daemon:daemon")
    // Add all files on the classpath
    copy(classpath.files, "./lib/", "daemon:daemon")
    // Add the JAR file
    copy(jarFile, jarTarget, "daemon:daemon")

    volume("/model")
    user("daemon")
    cmd("/hydro-serving/app/start.sh")
  }
}

imageNames in docker := Seq(
  ImageName(s"hydrosphere/${name.value}:${version.value}")
)

enablePlugins(BuildInfoPlugin)
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.gitCurrentBranch, git.gitCurrentTags, git.gitHeadCommit)
buildInfoPackage := "io.hydrosphere.serving.gateway"
buildInfoOptions += BuildInfoOption.ToJson
