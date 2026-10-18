package com.devopswithprashant.service.user

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import java.util.UUID
import munit.CatsEffectSuite

class UserProfileServiceSuite extends CatsEffectSuite:

  import UserProfileServiceError.*
  import TestSupport.*

  private def update(
    displayName: Option[String] = None,
    bio: Option[String] = None,
    avatarUrl: Option[String] = None,
    preferences: Json = Json.obj(),
    socialLinks: Json = Json.obj()
  ): UserProfileUpdate =
    UserProfileUpdate(displayName, bio, avatarUrl, preferences, socialLinks)

  test("creates a default profile and returns its identity"):
    val userId = UUID.randomUUID()
    for
      repo <- repository()
      created <- new UserProfileService[IO](repo).createDefaultProfile(userId)
      fetched <- repo.findByUserId(userId)
    yield
      assertEquals(created.userId, userId)
      assertEquals(fetched, Some(created))

  test("gets private and public profiles and reports missing profiles"):
    val userId = UUID.randomUUID()
    val missingId = UUID.randomUUID()
    val existing = profile(userId)
    for
      repo <- repository(Map(userId -> existing))
      service = new UserProfileService[IO](repo)
      privateResult <- service.getProfile(userId)
      publicResult <- service.getPublicProfile(userId)
      missingPrivate <- service.getProfile(missingId)
      missingPublic <- service.getPublicProfile(missingId)
    yield
      assertEquals(privateResult, Right(existing))
      assertEquals(publicResult.map(_.userId), Right(userId))
      assertEquals(missingPrivate, Left(ProfileNotFound(missingId)))
      assertEquals(missingPublic, Left(ProfileNotFound(missingId)))

  test("updates valid profile fields"):
    val userId = UUID.randomUUID()
    val existing = profile(userId)
    val request = update(
      displayName = Some("Ada"),
      bio = Some("Engineer"),
      avatarUrl = Some("https://images.example/avatar.png"),
      preferences = Json.obj("theme" -> Json.fromString("dark")),
      socialLinks = Json.obj("site" -> Json.fromString("https://example.test"))
    )
    for
      repo <- repository(Map(userId -> existing))
      result <- new UserProfileService[IO](repo).updateProfile(userId, request)
    yield
      assertEquals(result.map(_.displayName), Right(Some("Ada")))
      assertEquals(result.map(_.bio), Right(Some("Engineer")))
      assertEquals(result.map(_.preferences), Right(request.preferences))

  test("accepts exact text and avatar URL limits and counts Unicode code points"):
    val userId = UUID.randomUUID()
    val request = update(
      displayName = Some("😀" * 100),
      bio = Some("b" * 500),
      avatarUrl = Some(s"https://${"a" * 2032}.example")
    )
    for
      repo <- repository(Map(userId -> profile(userId)))
      result <- new UserProfileService[IO](repo).updateProfile(userId, request)
    yield assert(result.isRight)

  test("rejects overlong text, invalid URLs, and non-object JSON documents"):
    val userId = UUID.randomUUID()
    val request = update(
      displayName = Some("n" * 101),
      bio = Some("b" * 501),
      avatarUrl = Some("http://user:pass@example.test/avatar"),
      preferences = Json.arr(Json.fromInt(1)),
      socialLinks = Json.Null
    )
    for
      repo <- repository(Map(userId -> profile(userId)))
      result <- new UserProfileService[IO](repo).updateProfile(userId, request)
    yield
      val errors = result.left.toOption.collect { case InvalidUpdate(values) => values }.get
      assertEquals(errors.map(_.field).toSet, Set("displayName", "bio", "avatarUrl", "preferences", "socialLinks"))

  test("rejects malformed, relative, non-HTTPS, hostless, userinfo, and overlong avatar URLs"):
    val userId = UUID.randomUUID()
    val invalidUrls = List(
      "not a uri",
      "/relative/path",
      "http://example.test/a.png",
      "https:///path",
      "https://user@example.test/a.png",
      s"https://example.test/${"x" * 2040}"
    )
    for
      repo <- repository(Map(userId -> profile(userId)))
      service = new UserProfileService[IO](repo)
      results <- invalidUrls.traverse(url =>
        service.updateProfile(userId, update(avatarUrl = Some(url)))
      )
    yield assert(results.forall(_.left.toOption.exists(_.isInstanceOf[InvalidUpdate])))

  test("does not call update persistence when validation fails"):
    val userId = UUID.randomUUID()
    for
      repo <- repository()
      result <- new UserProfileService[IO](repo).updateProfile(
        userId,
        update(displayName = Some("x" * 101))
      )
      stored <- repo.findByUserId(userId)
    yield
      assert(result.left.toOption.exists(_.isInstanceOf[InvalidUpdate]))
      assertEquals(stored, None)

  test("returns not found when a valid update targets no profile"):
    val userId = UUID.randomUUID()
    for
      repo <- repository()
      result <- new UserProfileService[IO](repo).updateProfile(userId, update())
    yield assertEquals(result, Left(ProfileNotFound(userId)))
