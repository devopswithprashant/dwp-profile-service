package com.devopswithprashant.service.profile

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import java.util.UUID
import munit.FunSuite

class ProfileApiSuite extends FunSuite:

  test("public profile encoder exposes only the public fields"):
    val userId = UUID.randomUUID()
    val response = PublicProfileResponse(
      userId.toString,
      Some("Ada"),
      None,
      Some("https://example.test/avatar.png"),
      "2026-01-01T00:00Z"
    )
    val json = response.asJson
    assertEquals(json.hcursor.get[String]("userId"), Right(userId.toString))
    assertEquals(json.hcursor.get[Json]("bio"), Right(Json.Null))
    assert(json.hcursor.get[Json]("preferences").isLeft)
    assert(json.hcursor.get[Json]("socialLinks").isLeft)
    assertEquals(
      json.asObject.map(_.keys.toSet),
      Some(Set("userId", "displayName", "bio", "avatarUrl", "createdAt"))
    )

  test("private profile encoder includes profile settings and timestamps"):
    val json = ProfileResponse(
      UUID.randomUUID().toString,
      None,
      None,
      None,
      Json.obj("theme" -> Json.fromString("dark")),
      Json.obj("site" -> Json.fromString("https://example.test")),
      "created",
      "updated"
    ).asJson
    assertEquals(json.hcursor.get[Json]("preferences"), Right(Json.obj("theme" -> Json.fromString("dark"))))
    assertEquals(json.hcursor.get[Json]("socialLinks"), Right(Json.obj("site" -> Json.fromString("https://example.test"))))
    assertEquals(json.hcursor.get[String]("updatedAt"), Right("updated"))

  test("update decoder accepts valid objects and omitted nullable fields"):
    val decoded = decode[ProfileUpdateRequest](
      """{"preferences":{"theme":"dark"},"socialLinks":{}}"""
    )
    assert(decoded.isRight)
    assertEquals(decoded.map(_.displayName), Right(None))
    assertEquals(decoded.map(_.avatarUrl), Right(None))

  test("update decoder accepts all nullable fields and maps them to domain"):
    val decoded = decode[ProfileUpdateRequest](
      """{"displayName":"Ada","bio":"Engineer","avatarUrl":"https://example.test/a","preferences":{},"socialLinks":{}}"""
    )
    assertEquals(decoded.map(_.toDomain.displayName), Right(Some("Ada")))
    assertEquals(decoded.map(_.toDomain.bio), Right(Some("Engineer")))

  test("update decoder rejects missing required objects and non-object JSON"):
    val cases = List(
      """{"preferences":{}}""",
      """{"socialLinks":{}}""",
      """{"preferences":[],"socialLinks":{}}""",
      """{"preferences":{},"socialLinks":null}""",
      """{"displayName":4,"preferences":{},"socialLinks":{}}""",
      """not-json"""
    )
    cases.foreach(value => assert(decode[ProfileUpdateRequest](value).isLeft, value))
