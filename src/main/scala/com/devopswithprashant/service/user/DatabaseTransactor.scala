package com.devopswithprashant.service.user

import cats.effect.{Async, Resource}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts

object DatabaseTransactor:

  def create[F[_]: Async](config: DatabaseConfig): Resource[F, HikariTransactor[F]] =
    HikariTransactor.newHikariTransactor[F](
      driverClassName = "org.postgresql.Driver",
      url = s"jdbc:postgresql://${config.host}:${config.port}/${config.name}",
      user = config.username,
      pass = config.password,
      connectEC = ExecutionContexts.synchronous
    )