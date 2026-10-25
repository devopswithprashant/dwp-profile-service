package com.devopswithprashant.service.profile

import cats.effect.IO
import cats.syntax.all.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant
import java.util.Date
import java.util.UUID
import munit.CatsEffectSuite

class JwtAuthenticatorSuite extends CatsEffectSuite:

  import JwtAuthenticationError.*
  import TestSupport.jwtSecret

  private val authenticator = new JwtAuthenticator[IO](JwtConfig(jwtSecret))

  private def token(
    subject: String,
    issuer: String = JwtAuthenticator.Issuer,
    audience: String = JwtAuthenticator.Audience,
    secret: String = jwtSecret,
    expiresAt: Option[Instant] = Some(Instant.now().plusSeconds(300)),
    issuedAt: Option[Instant] = Some(Instant.now().minusSeconds(1)),
    notBefore: Option[Instant] = None
  ): String =
    val builder =
      JWT.create().withIssuer(issuer).withAudience(audience).withSubject(subject)
    expiresAt.foreach(value => builder.withExpiresAt(Date.from(value)))
    issuedAt.foreach(value => builder.withIssuedAt(Date.from(value)))
    notBefore.foreach(value => builder.withNotBefore(Date.from(value)))
    builder.sign(Algorithm.HMAC384(secret))

  test("accepts a valid token and extracts its subject UUID"):
    val userId = UUID.randomUUID()
    assertIO(authenticator.authenticate(Some(token(userId.toString))), Right(userId))

  test("rejects a missing token"):
    assertIO(authenticator.authenticate(None), Left(MissingToken))

  test("rejects empty, malformed, and unsigned tokens"):
    val cases = List("", "not-a-jwt", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhIn0.")
    cases.traverse_(value =>
      assertIO(authenticator.authenticate(Some(value)), Left(InvalidToken))
    )

  test("rejects tokens with a wrong signature, issuer, or audience"):
    val userId = UUID.randomUUID().toString
    val cases = List(
      token(userId, secret = "different-test-secret-with-at-least-48-bytes"),
      token(userId, issuer = "wrong-issuer"),
      token(userId, audience = "wrong-audience")
    )
    cases.traverse_(value =>
      assertIO(authenticator.authenticate(Some(value)), Left(InvalidToken))
    )

  test("rejects expired and missing-expiration tokens"):
    val userId = UUID.randomUUID().toString
    val cases = List(
      token(userId, expiresAt = Some(Instant.now().minusSeconds(10))),
      token(userId, expiresAt = None)
    )
    cases.traverse_(value =>
      assertIO(authenticator.authenticate(Some(value)), Left(InvalidToken))
    )

  test("rejects future issued-at and not-before claims"):
    val userId = UUID.randomUUID().toString
    val cases = List(
      token(userId, issuedAt = Some(Instant.now().plusSeconds(60))),
      token(userId, notBefore = Some(Instant.now().plusSeconds(60)))
    )
    cases.traverse_(value =>
      assertIO(authenticator.authenticate(Some(value)), Left(InvalidToken))
    )

  test("rejects missing, malformed, and noncanonical UUID subjects"):
    val noSubject =
      JWT.create()
        .withIssuer(JwtAuthenticator.Issuer)
        .withAudience(JwtAuthenticator.Audience)
        .withExpiresAt(Date.from(Instant.now().plusSeconds(300)))
        .sign(Algorithm.HMAC384(jwtSecret))
    val malformed = token("not-a-uuid")
    val noncanonical = token("00000000-0000-0000-0000-00000000000g")
    List(noSubject, malformed, noncanonical).traverse_(value =>
      assertIO(authenticator.authenticate(Some(value)), Left(InvalidToken))
    )
