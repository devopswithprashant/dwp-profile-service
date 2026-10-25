package com.devopswithprashant.service.profile

import munit.FunSuite

class ProfileServiceConfigSuite extends FunSuite:

  private val requiredEnvironment = Map(
    "DB_USER" -> "test-user",
    "DB_PASSWORD" -> "test-password",
    "JWT_SECRET" -> ("x" * 48)
  )

  test("uses documented defaults when optional environment values are absent"):
    val config = ProfileServiceConfig.fromEnvironment(requiredEnvironment)
    assertEquals(config.host.toString, "0.0.0.0")
    assertEquals(config.port.value, 8080)
    assertEquals(config.grpcPort, 9090)
    assertEquals(config.appEnv, "development")
    assertEquals(config.database.host, "localhost")
    assertEquals(config.database.port, 5432)
    assertEquals(config.database.name, "profiledb")

  test("loads configured HTTP, gRPC, database, and environment values"):
    val environment = requiredEnvironment ++ Map(
      "SERVER_HOST" -> "127.0.0.1",
      "SERVER_PORT" -> "8181",
      "GRPC_PORT" -> "9191",
      "APP_ENV" -> "test",
      "DB_HOST" -> "postgres",
      "DB_PORT" -> "5544",
      "DB_NAME" -> "profiles_test"
    )
    val config = ProfileServiceConfig.fromEnvironment(environment)
    assertEquals(config.host.toString, "127.0.0.1")
    assertEquals(config.port.value, 8181)
    assertEquals(config.grpcPort, 9191)
    assertEquals(config.appEnv, "test")
    assertEquals(config.database, DatabaseConfig("postgres", 5544, "profiles_test", "test-user", "test-password"))

  test("rejects malformed host and HTTP port"):
    List(
      requiredEnvironment.updated("SERVER_HOST", "not a host"),
      requiredEnvironment.updated("SERVER_PORT", "65536"),
      requiredEnvironment.updated("SERVER_PORT", "not-a-port")
    ).foreach(environment =>
      intercept[IllegalArgumentException] {
        ProfileServiceConfig.fromEnvironment(environment)
      }
    )

  test("rejects out-of-range or nonnumeric gRPC and database ports"):
    List(
      requiredEnvironment.updated("GRPC_PORT", "0"),
      requiredEnvironment.updated("GRPC_PORT", "65536"),
      requiredEnvironment.updated("GRPC_PORT", "grpc"),
      requiredEnvironment.updated("DB_PORT", "0"),
      requiredEnvironment.updated("DB_PORT", "65536"),
      requiredEnvironment.updated("DB_PORT", "postgres")
    ).foreach(environment =>
      intercept[IllegalArgumentException] {
        ProfileServiceConfig.fromEnvironment(environment)
      }
    )

  test("requires nonempty database credentials and JWT secret"):
    List(
      requiredEnvironment.removed("DB_USER"),
      requiredEnvironment.updated("DB_USER", ""),
      requiredEnvironment.removed("DB_PASSWORD"),
      requiredEnvironment.updated("DB_PASSWORD", ""),
      requiredEnvironment.removed("JWT_SECRET"),
      requiredEnvironment.updated("JWT_SECRET", "")
    ).foreach(environment =>
      intercept[IllegalArgumentException] {
        ProfileServiceConfig.fromEnvironment(environment)
      }
    )

  test("requires at least 48 UTF-8 bytes for the HS384 signing secret"):
    intercept[IllegalArgumentException] {
      ProfileServiceConfig.fromEnvironment(
        requiredEnvironment.updated("JWT_SECRET", "x" * 47)
      )
    }
    val multibyteSecret = "é" * 24
    assertEquals(
      ProfileServiceConfig.fromEnvironment(
        requiredEnvironment.updated("JWT_SECRET", multibyteSecret)
      ).jwt.secret,
      multibyteSecret
    )
