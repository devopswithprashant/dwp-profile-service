package com.devopswithprashant.service.user

import cats.effect.Async
import cats.syntax.all.*
import com.devopswithprashant.user.v1.user_profile.{
  CreateDefaultProfileRequest,
  CreateDefaultProfileResponse,
  UserProfileServiceFs2Grpc
}
import fs2.grpc.syntax.all.*
import io.grpc.{Metadata, Status}
import java.sql.SQLException
import java.util.UUID
import scala.util.control.NonFatal

final class UserProfileGrpcService[F[_]: Async](
  profileService: UserProfileService[F]
) extends UserProfileServiceFs2Grpc[F, Metadata]:

  override def createDefaultProfile(
    request: CreateDefaultProfileRequest,
    ctx: Metadata
  ): F[CreateDefaultProfileResponse] =
    parseUserId(request.userId) match
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

  private def parseUserId(value: String): Option[UUID] =
    Either
      .catchOnly[IllegalArgumentException](UUID.fromString(value))
      .toOption
      .filter(_.toString.equalsIgnoreCase(value))

object UserProfileGrpcServer:

  def resource[F[_]: Async](
    port: Int,
    profileService: UserProfileService[F]
  ): cats.effect.Resource[F, io.grpc.Server] =
    UserProfileServiceFs2Grpc
      .bindServiceResource[F](new UserProfileGrpcService(profileService))
      .flatMap { service =>
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
          .forPort(port)
          .addService(service)
          .resource[F]
          .evalTap(server => Async[F].delay(server.start()))
      }
