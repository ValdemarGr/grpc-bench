lazy val protos = project
  .in(file("protos"))
  .settings(
    name := "protos",
    scalaVersion := "3.3.4",
    scalacOptions += "-no-indent",
    Compile / PB.protoSources := Seq(file("proto")),
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
    )
  )
  .enablePlugins(Fs2Grpc)

lazy val program = project
  .in(file("program"))
  .settings(
    name := "program",
    scalaVersion := "3.3.4",
    scalacOptions += "-no-indent",
    Compile / run / fork := true,
    libraryDependencies ++= Seq(
      "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion,
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "org.typelevel" %% "log4cats-core" % "2.7.0",
      "org.typelevel" %% "log4cats-slf4j" % "2.7.0",
      "co.fs2" %% "fs2-core" % "3.10.2",
      "co.fs2" %% "fs2-io" % "3.10.2",
      "net.logstash.logback" % "logstash-logback-encoder" % "6.6",
      "ch.qos.logback" % "logback-classic" % "1.2.9",
    "org.typelevel" %% "log4cats-core"% "2.6.0",
    "org.typelevel" %% "log4cats-slf4j"% "2.6.0",
    "org.typelevel" %% "log4cats-noop"% "2.6.0",
    "org.typelevel" %% "log4cats-testing"% "2.6.0",
    ),
    assembly / mainClass := Some("bench.Main"),
    assembly / assemblyOutputPath := file("target/program.jar"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    }
  )
  .dependsOn(protos)
