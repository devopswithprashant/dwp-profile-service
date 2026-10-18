package com.devopswithprashant.service.user

import cats.Applicative
import cats.Functor
import cats.syntax.all.*
import io.circe.Json
import java.util.UUID

sealed trait UserProfileServiceError

object UserProfileServiceError:
  final case class ProfileNotFound(userId: UUID) extends UserProfileServiceError
  final case class InvalidUpdate(errors: List[ProfileUpdateValidationError])
      extends UserProfileServiceError

final case class ProfileUpdateValidationError(field: String, message: String)

final class UserProfileService[F[_]: Functor](
  repository: UserProfileRepository[F]
):

  import UserProfileServiceError.*

  def createDefaultProfile(userId: UUID): F[UserProfile] =
    repository.createDefaultProfile(userId)

  def getProfile(
    userId: UUID
  ): F[Either[UserProfileServiceError, UserProfile]] =
    repository
      .findByUserId(userId)
      .map(_.toRight(ProfileNotFound(userId)))

  def getPublicProfile(
    userId: UUID
  ): F[Either[UserProfileServiceError, PublicUserProfile]] =
    repository
      .findPublicByUserId(userId)
      .map(_.toRight(ProfileNotFound(userId)))

  def updateProfile(
    userId: UUID,
    update: UserProfileUpdate
  )(using Applicative[F]): F[Either[UserProfileServiceError, UserProfile]] =
    validate(update) match
      case Left(errors) => InvalidUpdate(errors).asLeft[UserProfile].pure[F]
      case Right(validUpdate) =>
        repository
          .updateProfile(userId, validUpdate)
          .map(_.toRight(ProfileNotFound(userId)))

  private def validate(
    update: UserProfileUpdate
  ): Either[List[ProfileUpdateValidationError], UserProfileUpdate] =
    val errors =
      List(
        validateJsonObject("preferences", update.preferences),
        validateJsonObject("socialLinks", update.socialLinks)
      ).flatten

    Either.cond(errors.isEmpty, update, errors)

  private def validateJsonObject(
    field: String,
    value: Json
  ): Option[ProfileUpdateValidationError] =
    Option.when(!value.isObject) {
      ProfileUpdateValidationError(field, "must be a JSON object")
    }
