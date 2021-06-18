import sbt._

object Dependencies {
  val akkaVersion = "2.6.9"
  val akkaHttpVersion = "10.2.0"
  val akkaHttpJsonVersion = "1.36.0"

  val log4j2Version = "2.13.3"
  val scalaTestVersion = "3.2.2"
  val servingGrpcScala = "3.0.0-dev3"
  val kafkaApiVersion = "2.8.0"
  val catsEffectVersion = "2.2.0"
  val fs2Version = "2.4.4"
  
  lazy val akkaDependencies = Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
  )

  lazy val akkaHttpDependencies = Seq(
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "de.heikoseeberger" %% "akka-http-circe" % akkaHttpJsonVersion,
    "ch.megard" %% "akka-http-cors" % "1.1.1"
  )

  lazy val streamDeps = Seq(
    "co.fs2" %% "fs2-core" % fs2Version
  )

  lazy val grpcDependencies = Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
    "io.hydrosphere" %% "serving-grpc-scala" % servingGrpcScala)

  lazy val kafkaDeps = Seq(
    "org.apache.kafka" %% "kafka" % kafkaApiVersion,
    "org.apache.kafka" % "kafka-clients" % kafkaApiVersion % Test,
    "org.apache.kafka" %% "kafka-streams-scala" % kafkaApiVersion
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
    "org.apache.logging.log4j" %% "log4j-api-scala" % "12.0"
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
      "com.github.pureconfig" %% "pureconfig" % "0.14.0"
    )
}
