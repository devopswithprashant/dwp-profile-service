package com.devopswithprashant.service.user

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import doobie.implicits.*
import net.logstash.logback.marker.Markers
import org.http4s.ember.server.EmberServerBuilder
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*

object DwpuserserviceServer:

  private val logger = LoggerFactory.getLogger("application.lifecycle")

  def run[F[_]: Async]: F[Nothing] =
    UserServiceConfig.load[F].flatMap { config =>
      serverResource[F](config).use(_ => Async[F].never[Nothing])
    }

  private[user] def serverResource[F[_]: Async](
    config: UserServiceConfig
  ): Resource[F, org.http4s.server.Server] =
    Resource.eval(DatabaseMigrations.migrate[F](config.database)) *>
      DatabaseTransactor.create[F](config.database).flatMap { transactor =>
        val startupCheck =
          sql"SELECT 1".query[Int].unique.transact(transactor).void
        Resource.eval(startupCheck) *>
          {
            val profileRepository =
              new DoobieUserProfileRepository[F](transactor)
            val profileService = new UserProfileService[F](profileRepository)
            val jwtAuthenticator = new JwtAuthenticator[F](config.jwt)
            val metrics = new ServiceMetrics()
            metrics.recordDatabaseHealth(isUp = true)
            val databaseHealth =
              DatabaseHealth.check(
                sql"SELECT 1".query[Int].unique.transact(transactor).void
              )
            val httpApp =
              DwpuserserviceRoutes
                .routes[F](
                  profileService,
                  jwtAuthenticator,
                  metrics,
                  databaseHealth
                )
                .orNotFound
            val finalHttpApp = HttpObservability.instrument(httpApp, metrics)
            UserProfileGrpcServer
              .resource[F](config.grpcPort, profileService, metrics)
              .flatMap { _ =>
                EmberServerBuilder
                  .default[F]
                  .withHost(config.host)
                  .withPort(config.port)
                  .withHttpApp(finalHttpApp)
                  .build
                  .evalTap(_ =>
                    Async[F].delay {
                      logger.info(
                        Markers.appendEntries(
                          Map[String, AnyRef](
                            "event" -> "service_started",
                            "environment" -> config.appEnv,
                            "http_port" -> Integer.valueOf(config.port.value),
                            "grpc_port" -> Integer.valueOf(config.grpcPort)
                          ).asJava
                        ),
                        "User Service started"
                      )
                    }
                  )
              }
          }
      }