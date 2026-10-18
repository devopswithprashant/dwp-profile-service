package com.devopswithprashant.service.user

import cats.effect.Sync
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

sealed trait JwtAuthenticationError

object JwtAuthenticationError:
  case object MissingToken extends JwtAuthenticationError
  case object InvalidToken extends JwtAuthenticationError

final class JwtAuthenticator[F[_]: Sync](config: JwtConfig):

  import JwtAuthenticationError.*

  private val verifier =
    JWT
      .require(Algorithm.HMAC384(config.secret.getBytes(StandardCharsets.UTF_8)))
      .withIssuer(JwtAuthenticator.Issuer)
      .withAudience(JwtAuthenticator.Audience)
      .build()

  def authenticate(
    token: Option[String]
  ): F[Either[JwtAuthenticationError, UUID]] =
    token match
      case None => Sync[F].pure(Left(MissingToken))
      case Some(value) =>
        Sync[F].delay {
          try
            val decoded = verifier.verify(value)
            val now = Instant.now()
            val hasValidExpiration =
              Option(decoded.getExpiresAt).exists(_.toInstant.isAfter(now))
            val hasValidIssuedAt =
              Option(decoded.getIssuedAt).forall(!_.toInstant.isAfter(now))
            val subject =
              Option(decoded.getSubject)
                .flatMap(parseCanonicalUuid)

            if hasValidExpiration && hasValidIssuedAt then
              subject.toRight(InvalidToken)
            else Left(InvalidToken)
          catch
            case _: JWTVerificationException => Left(InvalidToken)
            case _: IllegalArgumentException => Left(InvalidToken)
        }

  private def parseCanonicalUuid(value: String): Option[UUID] =
    Try(UUID.fromString(value))
      .toOption
      .filter(_.toString.equalsIgnoreCase(value))

object JwtAuthenticator:
  val Issuer = "devopswithprashant-auth"
  val Audience = "devopswithprashant-platform"
