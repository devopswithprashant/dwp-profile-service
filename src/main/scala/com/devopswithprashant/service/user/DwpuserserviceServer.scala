package com.devopswithprashant.service.user

import cats.effect.Async
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger

object DwpuserserviceServer:

  def run[F[_]: Async]: F[Nothing] =
    val httpApp =
      DwpuserserviceRoutes.routes[F].orNotFound

    val finalHttpApp =
      Logger.httpApp(true, true)(httpApp)

    EmberServerBuilder
      .default[F]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(finalHttpApp)
      .build
      .useForever