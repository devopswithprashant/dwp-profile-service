package com.devopswithprashant.service.user

import cats.effect.Sync
import com.comcast.ip4s.{Host, Port}

final case class DatabaseConfig(
  host: String,
  port: Int,
  name: String,
  username: String,
  password: String
)

final case class UserServiceConfig(
  host: Host,
  port: Port,
  grpcPort: Int,
  appEnv: String,
  database: DatabaseConfig
)

object UserServiceConfig:

  def load[F[_]: Sync]: F[UserServiceConfig] =
    Sync[F].delay {
      val hostValue =
        sys.env.getOrElse("SERVER_HOST", "0.0.0.0")

      val portValue =
        sys.env.getOrElse("SERVER_PORT", "8080")

      val appEnv =
        sys.env.getOrElse("APP_ENV", "development")

      val host =
        Host.fromString(hostValue).getOrElse {
          throw new IllegalArgumentException(
            s"Invalid SERVER_HOST value: '$hostValue'"
          )
        }

      val port =
        Port.fromString(portValue).getOrElse {
          throw new IllegalArgumentException(
            s"Invalid SERVER_PORT value: '$portValue'"
          )
        }

      val grpcPortValue =
        sys.env.getOrElse("GRPC_PORT", "9090")

      val grpcPort =
        grpcPortValue.toIntOption.filter(p => p >= 1 && p <= 65535).getOrElse {
          throw new IllegalArgumentException(
            s"Invalid GRPC_PORT value: '$grpcPortValue'"
          )
        }

      val databaseHost =
        sys.env.getOrElse("DB_HOST", "localhost")

      val databasePortValue =
        sys.env.getOrElse("DB_PORT", "5432")

      val databasePort =
        databasePortValue.toIntOption.filter(p => p >= 1 && p <= 65535).getOrElse {
          throw new IllegalArgumentException(
            s"Invalid DB_PORT value: '$databasePortValue'"
          )
        }

      val databaseName =
        sys.env.getOrElse("DB_NAME", "profiledb")

      val databaseUsername =
        sys.env.get("DB_USER").filter(_.nonEmpty).getOrElse {
          throw new IllegalArgumentException(
            "DB_USER environment variable must be set"
          )
        }

      val databasePassword =
        sys.env.get("DB_PASSWORD").filter(_.nonEmpty).getOrElse {
          throw new IllegalArgumentException(
            "DB_PASSWORD environment variable must be set"
          )
        }

      UserServiceConfig(
        host = host,
        port = port,
        grpcPort = grpcPort,
        appEnv = appEnv,
        database = DatabaseConfig(
          host = databaseHost,
          port = databasePort,
          name = databaseName,
          username = databaseUsername,
          password = databasePassword
        )
      )
    }
