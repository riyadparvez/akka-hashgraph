lazy val root = (project in file(".")).
  settings(
    name := "insight-test",
    version := "1.0",
    scalaVersion := "2.11.8"
  )

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.5.0"
libraryDependencies += "commons-io" % "commons-io" % "2.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"       % "2.4.2",
  "com.typesafe.akka" %% "akka-slf4j"       % "2.4.2",
  "com.typesafe.akka" %% "akka-remote"      % "2.4.2",
  "com.typesafe.akka" %% "akka-agent"       % "2.4.2",
  "com.typesafe.akka" %  "akka-stream_2.11" % "2.4.2",
  "com.typesafe.akka" %% "akka-persistence" % "2.4.2",
  "com.typesafe.akka" %% "akka-testkit"     % "2.4.2" % "test"
)
