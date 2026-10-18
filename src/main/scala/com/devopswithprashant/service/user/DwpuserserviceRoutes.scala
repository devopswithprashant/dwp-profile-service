package com.devopswithprashant.service.user

import cats.effect.Sync
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

object DwpuserserviceRoutes:

  def routes[F[_]: Sync]: HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {
      case GET -> Root / "health" =>
        val response = Json.obj(
          "status" -> Json.fromString("UP"),
          "service" -> Json.fromString("dwp-user-service")
        )

        Ok(response)
    }