package com.devopswithprashant.service.user

import cats.effect.Async
import cats.syntax.all.*
import doobie.implicits.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger

object DwpuserserviceServer:

  def run[F[_]: Async]: F[Nothing] =
    UserServiceConfig.load[F].flatMap { config =>
      DatabaseTransactor.create[F](config.database).use { transactor =>
        sql"SELECT 1".query[Int].unique.transact(transactor).void *>
          {
            val httpApp = DwpuserserviceRoutes.routes[F].orNotFound
            val finalHttpApp = Logger.httpApp(true, true)(httpApp)
            EmberServerBuilder
              .default[F]
              .withHost(config.host)
              .withPort(config.port)
              .withHttpApp(finalHttpApp)
              .build
              .useForever
          }
      }
    }