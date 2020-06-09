import sbt._

object Dependencies {
  val akkaVersion = "2.5.19"
  val akkaHttpVersion = "10.1.7"

  val log4j2Version = "2.8.2"
  val scalaTestVersion = "3.0.3"
  val servingGrpcScala = "2.3.0"
  val kafkaApiVersion = "2.2.0"
  val catsEffectVersion = "1.2.0"
  val fs2Version = "1.0.4"
  
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

  lazy val streamDeps = Seq(
    "co.fs2" %% "fs2-core" % fs2Version
  )

  lazy val grpcDependencies = Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
    "io.hydrosphere" %% "serving-grpc-scala" % servingGrpcScala,
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
  ).map(m => m.exclude("com.google.api.grpc", "googleapis-common-protos"))

  lazy val kafkaDeps = Seq(
    "org.apache.kafka" %% "kafka" % kafkaApiVersion,
    "org.apache.kafka" % "kafka-clients" % kafkaApiVersion % Test,
    "org.apache.kafka" %% "kafka-streams-scala" % kafkaApiVersion,
  )

  lazy val testDependencies = Seq(
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "test",
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

  lazy val all = logDependencies ++
    akkaDependencies ++
    testDependencies ++
    akkaHttpDependencies ++
    grpcDependencies ++
    streamDeps ++
    kafkaDeps ++
    Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.github.pureconfig" %% "pureconfig" % "0.9.1"
    )
}