package com.devopswithprashant.service.user

import io.circe.Json
import java.time.OffsetDateTime
import java.util.UUID

final case class UserProfile(
  userId: UUID,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  preferences: Json,
  socialLinks: Json,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)

final case class PublicUserProfile(
  userId: UUID,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  createdAt: OffsetDateTime
)

/** Replaces mutable profile fields; None clears nullable fields. */
final case class UserProfileUpdate(
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  preferences: Json,
  socialLinks: Json
)
