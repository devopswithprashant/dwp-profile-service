val Http4sVersion = "1.0.0-M29"
val MunitVersion = "0.7.29"
val LogbackVersion = "1.2.6"
val MunitCatsEffectVersion = "1.0.6"
val DoobieVersion = "1.0.0-RC12"
val FlywayVersion = "13.10.3"

lazy val root = (project in file("."))
  .enablePlugins(Fs2Grpc)
  .settings(
    organization := "com.devopswithprashant",
    name := "dwp-profile-service",
    version := "0.0.2",
    scalaVersion := "3.3.8",
    libraryDependencies ++= Seq(
      "org.http4s"      %% "http4s-ember-server" % Http4sVersion,
      "org.http4s"      %% "http4s-ember-client" % Http4sVersion,
      "org.http4s"      %% "http4s-circe"        % Http4sVersion,
      "org.http4s"      %% "http4s-dsl"          % Http4sVersion,
      "org.tpolecat"    %% "doobie-core"         % DoobieVersion,
      "org.tpolecat"    %% "doobie-hikari"       % DoobieVersion,
      "org.tpolecat"    %% "doobie-postgres"     % DoobieVersion,
      "org.tpolecat"    %% "doobie-postgres-circe" % DoobieVersion,
      "org.flywaydb"    %  "flyway-core"         % FlywayVersion,
      "org.flywaydb"    %  "flyway-database-postgresql" % FlywayVersion,
      "org.typelevel"   %% "fs2-grpc-runtime"     % "3.1.2",
      "io.grpc"         %  "grpc-netty-shaded"    % scalapb.compiler.Version.grpcJavaVersion,
      "com.auth0"       %  "java-jwt"             % "4.6.2",
      "io.prometheus"   %  "simpleclient"         % "0.16.0",
      "io.prometheus"   %  "simpleclient_common"  % "0.16.0",
      "org.scalameta"   %% "munit"               % MunitVersion           % Test,
      "org.typelevel"   %% "munit-cats-effect-3" % MunitCatsEffectVersion % Test,
      "net.logstash.logback" % "logstash-logback-encoder" % "6.6",
      "ch.qos.logback"  %  "logback-classic"     % LogbackVersion,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    assembly / mainClass := Some(
      "com.devopswithprashant.service.profile.Main"
    ),
    assembly / assemblyJarName := "dwp-profile-service.jar",
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _ @ _*) =>
        MergeStrategy.concat
      case PathList("META-INF", _ @ _*) =>
        MergeStrategy.discard
      case "reference.conf" =>
        MergeStrategy.concat
      case "application.conf" =>
        MergeStrategy.concat
      case _ =>
        MergeStrategy.first
    },
    coverageExcludedPackages := "com\\.devopswithprashant\\.profile\\.v1\\.profile.*",
    coverageMinimumStmtTotal := 90,
    coverageFailOnMinimum := true
  )
