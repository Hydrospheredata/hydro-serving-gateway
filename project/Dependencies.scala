import sbt._

object Dependencies {
  val akkaVersion = "2.5.8"
  val akkaHttpVersion = "10.0.11"
  val log4j2Version = "2.8.2"
  val scalaTestVersion = "3.0.3"
  val servingGrpcScala = "0.1.22"
  val envoyDataPlaneApi = "v1.6.0_1"
  val catsV = "1.1.0"

  lazy val akkaDependencies = Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
  )

  lazy val akkaHttpDependencies = Seq(
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion,
    "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.11.0" exclude("javax.ws.rs", "jsr311-api"),
    "ch.megard" %% "akka-http-cors" % "0.2.1"
  )

  lazy val grpcDependencies = Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion exclude("com.google.api.grpc", "proto-google-common-protos"),
    "io.hydrosphere" %% "serving-grpc-scala" % servingGrpcScala exclude("com.google.api.grpc", "proto-google-common-protos"),
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion exclude("com.google.api.grpc", "proto-google-common-protos"),
    "io.hydrosphere" %% "envoy-data-plane-api" % envoyDataPlaneApi exclude("com.google.api.grpc", "proto-google-common-protos")
  )

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
      "org.typelevel" %% "cats-core" % catsV,
      "com.github.pureconfig" %% "pureconfig" % "0.9.1"
    )
}