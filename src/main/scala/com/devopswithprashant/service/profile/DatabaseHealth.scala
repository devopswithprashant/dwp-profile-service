package com.devopswithprashant.service.profile

import cats.effect.Async
import cats.syntax.all.*
import net.logstash.logback.marker.Markers
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*

object DatabaseHealth:

  private val logger = LoggerFactory.getLogger("database.health")

  def check[F[_]: Async](probe: F[Unit]): F[Boolean] =
    probe.attempt.flatMap {
      case Right(_) => true.pure[F]
      case Left(error) =>
        Async[F].delay {
          logger.error(
            Markers.appendEntries(
              Map[String, AnyRef](
                "event" -> "database_health_check_failed",
                "error_type" -> error.getClass.getSimpleName
              ).asJava
            ),
            "Database readiness check failed"
          )
        }.as(false)
    }
