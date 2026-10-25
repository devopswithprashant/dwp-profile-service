package com.devopswithprashant.service.profile

import io.circe.Json
import java.time.OffsetDateTime
import java.util.UUID

final case class Profile(
  userId: UUID,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  preferences: Json,
  socialLinks: Json,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)

final case class PublicProfile(
  userId: UUID,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  createdAt: OffsetDateTime
)

/** Replaces mutable profile fields; None clears nullable fields. */
final case class ProfileUpdate(
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  preferences: Json,
  socialLinks: Json
)
