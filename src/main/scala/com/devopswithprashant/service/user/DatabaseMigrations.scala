package com.devopswithprashant.service.user

import cats.effect.Async
import org.flywaydb.core.Flyway

object DatabaseMigrations:

  def migrate[F[_]: Async](config: DatabaseConfig): F[Unit] =
    Async[F].blocking {
      Flyway
        .configure()
        .dataSource(
          s"jdbc:postgresql://${config.host}:${config.port}/${config.name}",
          config.username,
          config.password
        )
        .locations("classpath:db/migration")
        .defaultSchema("public")
        .schemas("public")
        .load()
        .migrate()
      ()
    }
