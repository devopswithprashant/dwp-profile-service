package com.devopswithprashant.service.user

import io.circe.{Decoder, DecodingFailure, Encoder, Json}

final case class PublicUserProfileResponse(
  userId: String,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  createdAt: String
)

final case class UserProfileResponse(
  userId: String,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  preferences: Json,
  socialLinks: Json,
  createdAt: String,
  updatedAt: String
)

object PublicUserProfileResponse:
  def fromProfile(profile: PublicUserProfile): PublicUserProfileResponse =
    PublicUserProfileResponse(
      userId = profile.userId.toString,
      displayName = profile.displayName,
      bio = profile.bio,
      avatarUrl = profile.avatarUrl,
      createdAt = profile.createdAt.toString
    )

  given Encoder[PublicUserProfileResponse] = Encoder.instance { response =>
    Json.obj(
      "userId" -> Json.fromString(response.userId),
      "displayName" -> response.displayName.fold(Json.Null)(Json.fromString),
      "bio" -> response.bio.fold(Json.Null)(Json.fromString),
      "avatarUrl" -> response.avatarUrl.fold(Json.Null)(Json.fromString),
      "createdAt" -> Json.fromString(response.createdAt)
    )
  }

object UserProfileResponse:
  def fromProfile(profile: UserProfile): UserProfileResponse =
    UserProfileResponse(
      userId = profile.userId.toString,
      displayName = profile.displayName,
      bio = profile.bio,
      avatarUrl = profile.avatarUrl,
      preferences = profile.preferences,
      socialLinks = profile.socialLinks,
      createdAt = profile.createdAt.toString,
      updatedAt = profile.updatedAt.toString
    )

  given Encoder[UserProfileResponse] = Encoder.instance { response =>
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

final case class UserProfileUpdateRequest(
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  preferences: Json,
  socialLinks: Json
):
  def toDomain: UserProfileUpdate =
    UserProfileUpdate(displayName, bio, avatarUrl, preferences, socialLinks)

object UserProfileUpdateRequest:
  given Decoder[UserProfileUpdateRequest] = Decoder.instance { cursor =>
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
    yield UserProfileUpdateRequest(
      displayName,
      bio,
      avatarUrl,
      preferences,
      socialLinks
    )
  }
