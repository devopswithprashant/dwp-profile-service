package com.devopswithprashant.service.profile

import cats.effect.Async
import cats.syntax.all.*
import com.devopswithprashant.profile.v1.profile.{
  CreateDefaultProfileRequest,
  CreateDefaultProfileResponse,
  ProfileServiceFs2Grpc
}
import fs2.grpc.syntax.all.*
import io.grpc.{Metadata, Status}
import java.sql.SQLException
import java.util.UUID
import net.logstash.logback.marker.Markers
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class ProfileGrpcService[F[_]: Async](
  profileService: ProfileService[F],
  metrics: ServiceMetrics
) extends ProfileServiceFs2Grpc[F, Metadata]:

  private val logger = LoggerFactory.getLogger("grpc.access")

  override def createDefaultProfile(
    request: CreateDefaultProfileRequest,
    ctx: Metadata
  ): F[CreateDefaultProfileResponse] =
    val result = parseUserId(request.userId) match
      case None =>
        Async[F].raiseError(
          Status.INVALID_ARGUMENT
            .withDescription("user_id must be a canonical UUID")
            .asRuntimeException()
        )
      case Some(userId) =>
        profileService
          .createDefaultProfile(userId)
          .as(CreateDefaultProfileResponse(userId = userId.toString))
          .handleErrorWith {
            case error: SQLException =>
              Async[F].raiseError(
                Status.UNAVAILABLE
                  .withDescription("Profile database is unavailable")
                  .withCause(error)
                  .asRuntimeException()
              )
            case NonFatal(error) =>
              Async[F].raiseError(
                Status.INTERNAL
                  .withDescription("Failed to create the default profile")
                  .withCause(error)
                  .asRuntimeException()
              )
          }

    result.attempt.flatMap {
      case Right(response) =>
        Async[F].delay {
          metrics.recordGrpc("CreateDefaultProfile", "OK")
          logger.info(
            Markers.appendEntries(
              Map[String, AnyRef](
                "event" -> "grpc_request",
                "method" -> "CreateDefaultProfile",
                "status" -> "OK"
              ).asJava
            ),
            "gRPC request completed"
          )
        } *>
          response.pure[F]
      case Left(error) =>
        val status = Status.fromThrowable(error).getCode.name()
        Async[F].delay {
          metrics.recordGrpc("CreateDefaultProfile", status)
          logger.warn(
            Markers.appendEntries(
              Map[String, AnyRef](
                "event" -> "grpc_request_failed",
                "method" -> "CreateDefaultProfile",
                "status" -> status
              ).asJava
            ),
            "gRPC request failed"
          )
        } *>
          Async[F].raiseError(error)
    }

  private def parseUserId(value: String): Option[UUID] =
    Either
      .catchOnly[IllegalArgumentException](UUID.fromString(value))
      .toOption
      .filter(_.toString.equalsIgnoreCase(value))

object ProfileGrpcServer:

  def resource[F[_]: Async](
    port: Int,
    profileService: ProfileService[F],
    metrics: ServiceMetrics
  ): cats.effect.Resource[F, io.grpc.Server] =
    ProfileServiceFs2Grpc
      .bindServiceResource[F](new ProfileGrpcService(profileService, metrics))
      .flatMap { service =>
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
          .forPort(port)
          .addService(service)
          .resource[F]
          .evalTap(server => Async[F].delay(server.start()))
      }
