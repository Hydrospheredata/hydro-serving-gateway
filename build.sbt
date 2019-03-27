organization := "io.hydrosphere.serving"
organizationName := "hydrosphere"
organizationHomepage := Some(url("https://hydrosphere.io"))

name := "hydro-serving-gateway"
version := IO.read(file("version")).trim

scalaVersion := "2.12.8"
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

parallelExecution in Test := false
parallelExecution in IntegrationTest := false
fork in(Test, test) := true
fork in(IntegrationTest, test) := true
fork in(IntegrationTest, testOnly) := true

libraryDependencies ++= Dependencies.hydroServingGatewayDependencies

enablePlugins(AshScriptPlugin)
bashScriptExtraDefines := Seq(
  "opts=\"$opts -Dsidecar.port=$SIDECAR_INGRESS_PORT\"",
  "opts=\"$opts -Dsidecar.host=$SIDECAR_HOST\"",
  "opts=\"$opts -Dapplication.http.port=$GATEWAY_HTTP_PORT\"",
  "opts=\"$opts -Dapplication.shadowing-on=$APP_SHADOWING_ON\"",
  "opts=\"$opts -Dakka.http.server.parsing.max-content-length=$MAX_CONTENT_LENGTH\"",
  "opts=\"$opts -Dakka.http.client.parsing.max-content-length=$MAX_CONTENT_LENGTH\"",
  "opts=\"$opts -Dapplication.grpc.deadline=$GRPC_DEADLINE\"",
  "opts=\"$opts -Dapplication.grpc.port=$GATEWAY_GRPC_PORT\"",
  "opts=\"$opts -Dapplication.grpc.max-message-size=$MAX_MESSAGE_SIZE\""
)
enablePlugins(DockerPlugin)
packageName in Docker := "hydrosphere/serving-gateway"
daemonUser in Docker := "daemon"
dockerBaseImage := "openjdk:8-jre-alpine"
dockerEnvVars := Map(
  "SIDECAR_INGRESS_PORT" -> "8080",
  "SIDECAR_HOST" -> "sidecar",
  "GATEWAY_HTTP_PORT" -> "9090",
  "GATEWAY_GRPC_PORT" ->"9091",
  "APP_SHADOWING_ON" -> "false",

  "MAX_CONTENT_LENGTH" -> "536870912",
  "MAX_MESSAGE_SIZE" -> "536870912",
  "GRPC_DEADLINE" -> "60seconds"
)
dockerExposedPorts := Seq(9090, 9091)
dockerLabels := Map(
  "DEPLOYMENT_TYPE" -> "APP",
  "SERVICE_ID" -> "-10",
  "RUNTIME_ID" -> "-10",
  "HS_SERVICE_MARKER" -> "HS_SERVICE_MARKER",
  "SERVICE_NAME" -> "gateway"
)

enablePlugins(BuildInfoPlugin)
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.gitCurrentBranch, git.gitCurrentTags, git.gitHeadCommit)
buildInfoPackage := "io.hydrosphere.serving.gateway"
buildInfoOptions += BuildInfoOption.ToJson