import sbt._

object Dependencies {
  val akkaVersion = "2.5.19"
  val akkaHttpVersion = "10.1.7"

  val log4j2Version = "2.8.2"
  val scalaTestVersion = "3.0.3"
  val servingGrpcScala = "2.0.0rc5"
  val envoyDataPlaneApi = "2.0.0"

  val catsVersion = "1.3.1"
  val catsEffectVersion = "1.0.0"

  lazy val akkaDependencies = Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
  )

  lazy val akkaHttpDependencies = Seq(
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
    "ch.megard" %% "akka-http-cors" % "0.3.4"
  )

  lazy val grpcDependencies = Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
    "io.hydrosphere" %% "serving-grpc-scala" % servingGrpcScala,
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
    "io.hydrosphere" %% "envoy-data-plane-api" % envoyDataPlaneApi
  ).map(m => m.exclude("com.google.api.grpc", "googleapis-common-protos"))

  lazy val testDependencies = Seq(
    "org.mockito" % "mockito-all" % "1.10.19" % "test",
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "test",
    "com.dimafeng" %% "testcontainers-scala" % "0.7.0" % "test",
    "org.scalactic" %% "scalactic" % scalaTestVersion % "test",
    "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
  )

  lazy val logDependencies = Seq(
    "org.apache.logging.log4j" % "log4j-api" % log4j2Version,
    "org.apache.logging.log4j" % "log4j-core" % log4j2Version,
    "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4j2Version,
    "org.apache.logging.log4j" %% "log4j-api-scala" % "11.0"
  )

  lazy val hydroServingGatewayDependencies = logDependencies ++
    akkaDependencies ++
    testDependencies ++
    akkaHttpDependencies ++
    grpcDependencies ++
    Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.github.pureconfig" %% "pureconfig" % "0.9.1"
    )
}
