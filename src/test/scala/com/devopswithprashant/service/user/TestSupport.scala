package com.devopswithprashant.service.user

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.Json
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

object TestSupport:

  val jwtSecret = "test-only-secret-that-is-at-least-48-bytes-long!"

  def profile(userId: UUID): UserProfile =
    val now = OffsetDateTime.now()
    UserProfile(
      userId = userId,
      displayName = None,
      bio = None,
      avatarUrl = None,
      preferences = Json.obj(),
      socialLinks = Json.obj(),
      createdAt = now,
      updatedAt = now
    )

  final class InMemoryRepository(
    ref: Ref[IO, Map[UUID, UserProfile]],
    createFailure: Option[Throwable] = None
  ) extends UserProfileRepository[IO]:

    override def findByUserId(userId: UUID): IO[Option[UserProfile]] =
      ref.get.map(_.get(userId))

    override def findPublicByUserId(
      userId: UUID
    ): IO[Option[PublicUserProfile]] =
      findByUserId(userId).map(_.map { value =>
        PublicUserProfile(
          value.userId,
          value.displayName,
          value.bio,
          value.avatarUrl,
          value.createdAt
        )
      })

    override def createDefaultProfile(userId: UUID): IO[UserProfile] =
      createFailure match
        case Some(error) => IO.raiseError(error)
        case None =>
          ref
            .modify { profiles =>
              profiles.get(userId) match
                case Some(existing) => (profiles, existing)
                case None =>
                  val created = profile(userId)
                  (profiles.updated(userId, created), created)
            }

    override def updateProfile(
      userId: UUID,
      update: UserProfileUpdate
    ): IO[Option[UserProfile]] =
      ref.modify { profiles =>
        profiles.get(userId) match
          case None => (profiles, None)
          case Some(existing) =>
            val updated = existing.copy(
              displayName = update.displayName,
              bio = update.bio,
              avatarUrl = update.avatarUrl,
              preferences = update.preferences,
              socialLinks = update.socialLinks,
              updatedAt = OffsetDateTime.now()
            )
            (profiles.updated(userId, updated), Some(updated))
      }

  def repository(
    initial: Map[UUID, UserProfile] = Map.empty,
    createFailure: Option[Throwable] = None
  ): IO[InMemoryRepository] =
    Ref.of[IO, Map[UUID, UserProfile]](initial)
      .map(new InMemoryRepository(_, createFailure))
