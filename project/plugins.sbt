logLevel := Level.Info

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.8.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.1")
addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.8.2")
//addSbtPlugin("com.codecommit" % "sbt-github-packages" % "0.5.3")

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "compilerplugin" % "0.11.3",
  "com.spotify" % "docker-client" % "8.16.0"
)
