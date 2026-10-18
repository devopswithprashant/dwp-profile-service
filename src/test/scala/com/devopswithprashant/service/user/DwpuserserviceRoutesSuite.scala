package com.devopswithprashant.service.user

import cats.effect.IO
import cats.syntax.all.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.circe.Json
import java.time.Instant
import java.util.Date
import java.util.UUID
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.implicits.*
import org.typelevel.ci.CIString

class DwpuserserviceRoutesSuite extends CatsEffectSuite:

  import TestSupport.*

  private val authenticator =
    new JwtAuthenticator[IO](JwtConfig(jwtSecret))

  private def token(userId: UUID): String =
    JWT.create()
      .withIssuer(JwtAuthenticator.Issuer)
      .withAudience(JwtAuthenticator.Audience)
      .withSubject(userId.toString)
      .withExpiresAt(Date.from(Instant.now().plusSeconds(300)))
      .sign(Algorithm.HMAC384(jwtSecret))

  private def app(repository: UserProfileRepository[IO]): HttpApp[IO] =
    DwpuserserviceRoutes
      .routes[IO](new UserProfileService[IO](repository), authenticator)
      .orNotFound

  private def request(
    method: Method,
    uri: Uri,
    userId: Option[UUID] = None,
    body: Option[Json] = None
  ): Request[IO] =
    val base = Request[IO](method, uri)
    val authenticated = userId.fold(base) { id =>
      base.putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, token(id)))
      )
    }
    body.fold(authenticated)(authenticated.withEntity(_))

  test("health endpoint returns the service status"):
    for
      repo <- repository()
      response <- app(repo).run(request(Method.GET, uri"/health"))
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body.hcursor.get[String]("status"), Right("UP"))

  test("public endpoint returns only allowlisted profile fields"):
    val userId = UUID.randomUUID()
    val profileWithPrivateData = profile(userId).copy(
      preferences = Json.obj("theme" -> Json.fromString("dark")),
      socialLinks = Json.obj("secret" -> Json.fromString("private"))
    )
    for
      repo <- repository(Map(userId -> profileWithPrivateData))
      response <- app(repo).run(
        request(
          Method.GET,
          Uri.unsafeFromString(s"/api/v1/users/$userId")
        )
      )
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assert(body.hcursor.get[Json]("preferences").isLeft)
      assert(body.hcursor.get[Json]("socialLinks").isLeft)

  test("public endpoint rejects malformed UUID and reports a missing profile"):
    for
      repo <- repository()
      service = app(repo)
      malformed <- service.run(request(Method.GET, uri"/api/v1/users/not-a-uuid"))
      missingId = UUID.randomUUID()
      missing <- service.run(
        request(
          Method.GET,
          Uri.unsafeFromString(s"/api/v1/users/$missingId")
        )
      )
    yield
      assertEquals(malformed.status, Status.BadRequest)
      assertEquals(missing.status, Status.NotFound)

  test("unknown route returns 404"):
    for
      repo <- repository()
      response <- app(repo).run(request(Method.GET, uri"/unknown"))
    yield assertEquals(response.status, Status.NotFound)

  test("private profile route requires a valid Bearer JWT"):
    val userId = UUID.randomUUID()
    for
      repo <- repository(Map(userId -> profile(userId)))
      service = app(repo)
      missing <- service.run(request(Method.GET, uri"/api/v1/users/me"))
      malformedScheme <- service.run(
        Request[IO](Method.GET, uri"/api/v1/users/me")
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Basic, "bad")))
      )
      invalid <- service.run(
        Request[IO](Method.GET, uri"/api/v1/users/me")
          .putHeaders(
            Authorization(Credentials.Token(AuthScheme.Bearer, "invalid"))
          )
      )
    yield
      assertEquals(missing.status, Status.Unauthorized)
      assertEquals(malformedScheme.status, Status.Unauthorized)
      assertEquals(invalid.status, Status.Unauthorized)
      assertEquals(missing.headers.get(CIString("WWW-Authenticate")).map(_.head.value), Some("Bearer"))

  test("authenticated GET uses JWT subject and returns private profile"):
    val userId = UUID.randomUUID()
    for
      repo <- repository(Map(userId -> profile(userId)))
      response <- app(repo).run(
        request(Method.GET, uri"/api/v1/users/me", Some(userId))
      )
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assert(body.hcursor.get[Json]("preferences").isRight)
      assertEquals(body.hcursor.get[String]("userId"), Right(userId.toString))

  test("authenticated GET reports a profile missing for the JWT subject"):
    val userId = UUID.randomUUID()
    for
      repo <- repository()
      response <- app(repo).run(
        request(Method.GET, uri"/api/v1/users/me", Some(userId))
      )
    yield assertEquals(response.status, Status.NotFound)

  test("PUT updates an existing profile and clears omitted nullable fields"):
    val userId = UUID.randomUUID()
    val update = Json.obj(
      "displayName" -> Json.fromString("Ada"),
      "preferences" -> Json.obj("theme" -> Json.fromString("dark")),
      "socialLinks" -> Json.obj()
    )
    for
      repo <- repository(Map(userId -> profile(userId).copy(bio = Some("old"))))
      response <- app(repo).run(
        request(Method.PUT, uri"/api/v1/users/me", Some(userId), Some(update))
      )
      body <- response.as[Json]
      stored <- repo.findByUserId(userId)
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body.hcursor.get[String]("displayName"), Right("Ada"))
      assertEquals(stored.flatMap(_.bio), None)

  test("PUT rejects malformed schema, invalid updates, and missing profiles"):
    val userId = UUID.randomUUID()
    val malformedSchema = Json.obj("preferences" -> Json.obj())
    val invalidUpdate = Json.obj(
      "displayName" -> Json.fromString("n" * 101),
      "preferences" -> Json.obj(),
      "socialLinks" -> Json.obj()
    )
    for
      repo <- repository()
      service = app(repo)
      invalidShape <- service.run(
        request(
          Method.PUT,
          uri"/api/v1/users/me",
          Some(userId),
          Some(malformedSchema)
        )
      )
      overlong <- service.run(
        request(
          Method.PUT,
          uri"/api/v1/users/me",
          Some(userId),
          Some(invalidUpdate)
        )
      )
      missing <- service.run(
        request(
          Method.PUT,
          uri"/api/v1/users/me",
          Some(userId),
          Some(
            Json.obj("preferences" -> Json.obj(), "socialLinks" -> Json.obj())
          )
        )
      )
    yield
      assertEquals(invalidShape.status, Status.BadRequest)
      assertEquals(overlong.status, Status.BadRequest)
      assertEquals(missing.status, Status.NotFound)

  test("PUT rejects malformed JSON entities"):
    val userId = UUID.randomUUID()
    for
      repo <- repository()
      request = Request[IO](Method.PUT, uri"/api/v1/users/me")
        .putHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, token(userId))),
          `Content-Type`(MediaType.application.json)
        )
        .withEntity("{")
      response <- app(repo).run(request)
    yield assertEquals(response.status, Status.BadRequest)
