package com.devopswithprashant.service.user

import cats.effect.Sync
import com.comcast.ip4s.{Host, Port}

final case class UserServiceConfig(
    host: Host,
    port: Port,
    appEnv: String
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

      UserServiceConfig(
        host = host,
        port = port,
        appEnv = appEnv
      )
    }