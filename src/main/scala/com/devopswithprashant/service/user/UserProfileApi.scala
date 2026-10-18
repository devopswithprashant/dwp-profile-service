package com.devopswithprashant.service.user

import io.circe.{Decoder, DecodingFailure, Encoder, Json}

final case class PublicUserProfileResponse(
  userId: String,
  displayName: Option[String],
  bio: Option[String],
  avatarUrl: Option[String],
  createdAt: String
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
