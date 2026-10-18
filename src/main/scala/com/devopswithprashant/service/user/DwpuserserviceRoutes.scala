package com.devopswithprashant.service.user

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import java.util.UUID
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.{AuthScheme, Credentials, Header, Request, Response, Status}
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString

object DwpuserserviceRoutes:

  private def parseUserId(value: String): Option[UUID] =
    Either
      .catchOnly[IllegalArgumentException](UUID.fromString(value))
      .toOption
      .filter(_.toString.equalsIgnoreCase(value))

  def routes[F[_]: Async](
    profileService: UserProfileService[F],
    jwtAuthenticator: JwtAuthenticator[F],
    metrics: ServiceMetrics,
    databaseHealth: F[Boolean]
  ): HttpRoutes[F] =
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

    def unauthorized: F[org.http4s.Response[F]] =
      Response[F](status = Status.Unauthorized).withEntity(
        Json.obj(
          "error" -> Json.fromString("unauthorized"),
          "message" -> Json.fromString("A valid Bearer token is required")
        )
      ).putHeaders(Header.Raw(CIString("WWW-Authenticate"), "Bearer")).pure[F]

    def authenticatedUserId(
      request: Request[F]
    ): F[Either[JwtAuthenticationError, UUID]] =
      val token =
        request.headers.get[Authorization].collect {
          case Authorization(Credentials.Token(AuthScheme.Bearer, value)) => value
        }
      jwtAuthenticator.authenticate(token)

    HttpRoutes.of[F] {
      case GET -> Root / "health" =>
        databaseHealth.flatMap { databaseUp =>
          metrics.recordDatabaseHealth(databaseUp)
          val status = if databaseUp then Status.Ok else Status.ServiceUnavailable
          val response = Json.obj(
            "status" -> Json.fromString(if databaseUp then "UP" else "DOWN"),
            "service" -> Json.fromString("dwp-user-service"),
            "components" -> Json.obj(
              "database" -> Json.fromString(if databaseUp then "UP" else "DOWN")
            )
          )
          Response[F](status = status).withEntity(response).pure[F]
        }

      case GET -> Root / "health" / "live" =>
        Ok(
          Json.obj(
            "status" -> Json.fromString("UP"),
            "service" -> Json.fromString("dwp-user-service")
          )
        )

      case GET -> Root / "metrics" =>
        Async[F].delay {
          Response[F](status = Status.Ok)
            .withEntity(metrics.renderPrometheus)
            .putHeaders(
              Header.Raw(
                CIString("Content-Type"),
                "text/plain; version=0.0.4; charset=utf-8"
              )
            )
        }

      case request @ GET -> Root / "api" / "v1" / "users" / "me" =>
        authenticatedUserId(request).flatMap {
          case Left(_) => unauthorized
          case Right(userId) =>
            profileService.getProfile(userId).flatMap {
              case Right(profile) => Ok(UserProfileResponse.fromProfile(profile))
              case Left(error) => errorResponse(error)
            }
        }

      case request @ PUT -> Root / "api" / "v1" / "users" / "me" =>
        authenticatedUserId(request).flatMap {
          case Left(_) => unauthorized
          case Right(userId) =>
            request.attemptAs[UserProfileUpdateRequest].value.flatMap {
              case Left(_) =>
                BadRequest(
                  Json.obj(
                    "error" -> Json.fromString("invalid_request"),
                    "message" -> Json.fromString("Request body does not match the profile update schema")
                  )
                )
              case Right(update) =>
                profileService.updateProfile(userId, update.toDomain).flatMap {
                  case Right(profile) =>
                    Ok(UserProfileResponse.fromProfile(profile))
                  case Left(error) => errorResponse(error)
                }
            }
        }

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