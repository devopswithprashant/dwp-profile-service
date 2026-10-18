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
    name := "dwp-user-service",
    version := "0.0.1-SNAPSHOT",
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
      "org.scalameta"   %% "munit"               % MunitVersion           % Test,
      "org.typelevel"   %% "munit-cats-effect-3" % MunitCatsEffectVersion % Test,
      "ch.qos.logback"  %  "logback-classic"     % LogbackVersion,
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
