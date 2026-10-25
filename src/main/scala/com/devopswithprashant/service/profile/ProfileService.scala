package com.devopswithprashant.service.profile

import cats.Applicative
import cats.Functor
import cats.syntax.all.*
import io.circe.Json
import java.net.URI
import java.util.UUID

sealed trait ProfileServiceError

object ProfileServiceError:
  final case class ProfileNotFound(userId: UUID) extends ProfileServiceError
  final case class InvalidUpdate(errors: List[ProfileUpdateValidationError])
      extends ProfileServiceError

final case class ProfileUpdateValidationError(field: String, message: String)

final class ProfileService[F[_]: Functor](
  repository: ProfileRepository[F]
):

  import ProfileServiceError.*

  def createDefaultProfile(userId: UUID): F[Profile] =
    repository.createDefaultProfile(userId)

  def getProfile(
    userId: UUID
  ): F[Either[ProfileServiceError, Profile]] =
    repository
      .findByUserId(userId)
      .map(_.toRight(ProfileNotFound(userId)))

  def getPublicProfile(
    userId: UUID
  ): F[Either[ProfileServiceError, PublicProfile]] =
    repository
      .findPublicByUserId(userId)
      .map(_.toRight(ProfileNotFound(userId)))

  def updateProfile(
    userId: UUID,
    update: ProfileUpdate
  )(using Applicative[F]): F[Either[ProfileServiceError, Profile]] =
    validate(update) match
      case Left(errors) => InvalidUpdate(errors).asLeft[Profile].pure[F]
      case Right(validUpdate) =>
        repository
          .updateProfile(userId, validUpdate)
          .map(_.toRight(ProfileNotFound(userId)))

  private def validate(
    update: ProfileUpdate
  ): Either[List[ProfileUpdateValidationError], ProfileUpdate] =
    val errors =
      List(
        validateText("displayName", update.displayName, 100),
        validateText("bio", update.bio, 500),
        validateAvatarUrl(update.avatarUrl),
        validateJsonObject("preferences", update.preferences),
        validateJsonObject("socialLinks", update.socialLinks)
      ).flatten

    Either.cond(errors.isEmpty, update, errors)

  private def validateText(
    field: String,
    value: Option[String],
    maximumLength: Int
  ): Option[ProfileUpdateValidationError] =
    Option.when(
      value.exists(text => text.codePointCount(0, text.length) > maximumLength)
    ) {
      ProfileUpdateValidationError(field, s"must be at most $maximumLength characters")
    }

  private def validateAvatarUrl(
    value: Option[String]
  ): Option[ProfileUpdateValidationError] =
    value.flatMap { url =>
      val valid =
        url.codePointCount(0, url.length) <= 2048 &&
          Either
            .catchOnly[IllegalArgumentException](URI.create(url))
            .exists(uri =>
              uri.isAbsolute &&
                Option(uri.getScheme).exists(_.equalsIgnoreCase("https")) &&
                Option(uri.getHost).exists(_.nonEmpty) &&
                uri.getRawUserInfo == null
            )

      Option.when(!valid) {
        ProfileUpdateValidationError(
          "avatarUrl",
          "must be an absolute HTTPS URL without user information and at most 2048 characters"
        )
      }
    }

  private def validateJsonObject(
    field: String,
    value: Json
  ): Option[ProfileUpdateValidationError] =
    Option.when(!value.isObject) {
      ProfileUpdateValidationError(field, "must be a JSON object")
    }
