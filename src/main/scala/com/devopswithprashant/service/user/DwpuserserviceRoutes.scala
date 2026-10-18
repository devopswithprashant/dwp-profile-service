package com.devopswithprashant.service.user

import cats.effect.Sync
import cats.syntax.all.*
import io.circe.Json
import java.util.UUID
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

object DwpuserserviceRoutes:

  private def parseUserId(value: String): Option[UUID] =
    Either
      .catchOnly[IllegalArgumentException](UUID.fromString(value))
      .toOption
      .filter(_.toString.equalsIgnoreCase(value))

  def routes[F[_]: Sync](profileService: UserProfileService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    def errorResponse(error: UserProfileServiceError): F[org.http4s.Response[F]] =
      error match
        case UserProfileServiceError.ProfileNotFound(userId) =>
          NotFound(
            Json.obj(
              "error" -> Json.fromString("profile_not_found"),
              "userId" -> Json.fromString(userId.toString)
            )
          )
        case UserProfileServiceError.InvalidUpdate(errors) =>
          BadRequest(
            Json.obj(
              "error" -> Json.fromString("invalid_update"),
              "details" -> Json.arr(errors.map { error =>
                Json.obj(
                  "field" -> Json.fromString(error.field),
                  "message" -> Json.fromString(error.message)
                )
              }*)
            )
          )

    HttpRoutes.of[F] {
      case GET -> Root / "health" =>
        val response = Json.obj(
          "status" -> Json.fromString("UP"),
          "service" -> Json.fromString("dwp-user-service")
        )

        Ok(response)

      case GET -> Root / "api" / "v1" / "users" / "me" =>
        NotImplemented(
          Json.obj(
            "error" -> Json.fromString("authentication_not_implemented"),
            "message" -> Json.fromString("Authenticated profile routes require JWT validation")
          )
        )

      case PUT -> Root / "api" / "v1" / "users" / "me" =>
        NotImplemented(
          Json.obj(
            "error" -> Json.fromString("authentication_not_implemented"),
            "message" -> Json.fromString("Authenticated profile routes require JWT validation")
          )
        )

      case GET -> Root / "api" / "v1" / "users" / userId =>
        parseUserId(userId) match
          case None =>
            BadRequest(
              Json.obj(
                "error" -> Json.fromString("invalid_user_id"),
                "message" -> Json.fromString("userId must be a UUID")
              )
            )
          case Some(id) =>
            profileService.getPublicProfile(id).flatMap {
              case Right(profile) =>
                Ok(PublicUserProfileResponse.fromProfile(profile))
              case Left(error) => errorResponse(error)
            }
    }