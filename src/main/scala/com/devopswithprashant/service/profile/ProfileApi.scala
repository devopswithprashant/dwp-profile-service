package com.devopswithprashant.service.profile

import io.circe.{Decoder, DecodingFailure, Encoder, Json}

final case class PublicProfileResponse(
  userId: String,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  createdAt: String
)

final case class ProfileResponse(
  userId: String,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  preferences: Json,
  socialLinks: Json,
  createdAt: String,
  updatedAt: String
)

object PublicProfileResponse:
  def fromProfile(profile: PublicProfile): PublicProfileResponse =
    PublicProfileResponse(
      userId = profile.userId.toString,
      displayName = profile.displayName,
      bio = profile.bio,
      avatarUrl = profile.avatarUrl,
      createdAt = profile.createdAt.toString
    )

  given Encoder[PublicProfileResponse] = Encoder.instance { response =>
    Json.obj(
      "userId" -> Json.fromString(response.userId),
      "displayName" -> response.displayName.fold(Json.Null)(Json.fromString),
      "bio" -> response.bio.fold(Json.Null)(Json.fromString),
      "avatarUrl" -> response.avatarUrl.fold(Json.Null)(Json.fromString),
      "createdAt" -> Json.fromString(response.createdAt)
    )
  }

object ProfileResponse:
  def fromProfile(profile: Profile): ProfileResponse =
    ProfileResponse(
      userId = profile.userId.toString,
      displayName = profile.displayName,
      bio = profile.bio,
      avatarUrl = profile.avatarUrl,
      preferences = profile.preferences,
      socialLinks = profile.socialLinks,
      createdAt = profile.createdAt.toString,
      updatedAt = profile.updatedAt.toString
    )

  given Encoder[ProfileResponse] = Encoder.instance { response =>
    Json.obj(
      "userId" -> Json.fromString(response.userId),
      "displayName" -> response.displayName.fold(Json.Null)(Json.fromString),
      "bio" -> response.bio.fold(Json.Null)(Json.fromString),
      "avatarUrl" -> response.avatarUrl.fold(Json.Null)(Json.fromString),
      "preferences" -> response.preferences,
      "socialLinks" -> response.socialLinks,
      "createdAt" -> Json.fromString(response.createdAt),
      "updatedAt" -> Json.fromString(response.updatedAt)
    )
  }

final case class ProfileUpdateRequest(
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  preferences: Json,
  socialLinks: Json
):
  def toDomain: ProfileUpdate =
    ProfileUpdate(displayName, bio, avatarUrl, preferences, socialLinks)

object ProfileUpdateRequest:
  given Decoder[ProfileUpdateRequest] = Decoder.instance { cursor =>
    for
      displayName <- cursor.get[Option[String]]("displayName")
      bio <- cursor.get[Option[String]]("bio")
      avatarUrl <- cursor.get[Option[String]]("avatarUrl")
      preferences <- cursor.get[Json]("preferences")
      socialLinks <- cursor.get[Json]("socialLinks")
      _ <- Either.cond(
        preferences.isObject,
        (),
        DecodingFailure("preferences must be a JSON object", cursor.history)
      )
      _ <- Either.cond(
        socialLinks.isObject,
        (),
        DecodingFailure("socialLinks must be a JSON object", cursor.history)
      )
    yield ProfileUpdateRequest(
      displayName,
      bio,
      avatarUrl,
      preferences,
      socialLinks
    )
  }
