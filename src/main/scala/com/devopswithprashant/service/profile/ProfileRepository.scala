package com.devopswithprashant.service.profile

import cats.effect.Async
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import java.time.OffsetDateTime
import java.util.UUID

trait ProfileRepository[F[_]]:
  def findByUserId(userId: UUID): F[Option[Profile]]
  def findPublicByUserId(userId: UUID): F[Option[PublicProfile]]
  def createDefaultProfile(userId: UUID): F[Profile]
  def updateProfile(userId: UUID, update: ProfileUpdate): F[Option[Profile]]

final class DoobieProfileRepository[F[_]: Async](
  transactor: Transactor[F]
) extends ProfileRepository[F]:

  private type ProfileRow = (
    UUID,
    Option[String],
    Option[String],
    Option[String],
    Json,
    Json,
    OffsetDateTime,
    OffsetDateTime
  )

  private def toProfile(row: ProfileRow): Profile =
    Profile(
      userId = row._1,
      displayName = row._2,
      bio = row._3,
      avatarUrl = row._4,
      preferences = row._5,
      socialLinks = row._6,
      createdAt = row._7,
      updatedAt = row._8
    )

  private def findByUserIdConnection(
    userId: UUID
  ): ConnectionIO[Option[Profile]] =
    sql"""
      SELECT user_id, display_name, bio, avatar_url,
             preferences, social_links, created_at, updated_at
      FROM profiles
      WHERE user_id = $userId
    """.query[ProfileRow].option.map(_.map(toProfile))

  override def findByUserId(userId: UUID): F[Option[Profile]] =
    findByUserIdConnection(userId).transact(transactor)

  override def findPublicByUserId(userId: UUID): F[Option[PublicProfile]] =
    sql"""
      SELECT user_id, display_name, bio, avatar_url, created_at
      FROM profiles
      WHERE user_id = $userId
    """.query[(UUID, Option[String], Option[String], Option[String], OffsetDateTime)]
      .option
      .map(_.map { case (id, displayName, bio, avatarUrl, createdAt) =>
        PublicProfile(id, displayName, bio, avatarUrl, createdAt)
      })
      .transact(transactor)

  override def createDefaultProfile(userId: UUID): F[Profile] =
    sql"""
      INSERT INTO profiles (user_id)
      VALUES ($userId)
      ON CONFLICT (user_id) DO NOTHING
      RETURNING user_id, display_name, bio, avatar_url,
                preferences, social_links, created_at, updated_at
    """.query[ProfileRow].option.flatMap {
      case Some(row) => toProfile(row).pure[ConnectionIO]
      case None =>
        findByUserIdConnection(userId).flatMap {
          case Some(profile) => profile.pure[ConnectionIO]
          case None =>
            new IllegalStateException(
              s"Profile for user $userId was not available after idempotent creation"
            ).raiseError[ConnectionIO, Profile]
        }
    }.transact(transactor)

  override def updateProfile(
    userId: UUID,
    update: ProfileUpdate
  ): F[Option[Profile]] =
    sql"""
      UPDATE profiles
      SET display_name = ${update.displayName},
          bio = ${update.bio},
          avatar_url = ${update.avatarUrl},
          preferences = ${update.preferences},
          social_links = ${update.socialLinks},
          updated_at = CURRENT_TIMESTAMP
      WHERE user_id = $userId
      RETURNING user_id, display_name, bio, avatar_url,
                preferences, social_links, created_at, updated_at
    """.query[ProfileRow].option.map(_.map(toProfile)).transact(transactor)
