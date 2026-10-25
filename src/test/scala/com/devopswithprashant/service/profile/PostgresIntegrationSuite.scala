package com.devopswithprashant.service.profile

import cats.effect.IO
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import com.comcast.ip4s.*
import io.circe.Json
import java.net.ServerSocket
import java.util.UUID
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*

class PostgresIntegrationSuite extends CatsEffectSuite:

  private lazy val config =
    val environment = sys.env
    val portValue = environment.getOrElse("PROFILE_TEST_DB_PORT", "5432")
    val port = portValue.toIntOption.filter(value => value >= 1 && value <= 65535)
      .getOrElse {
        throw new IllegalArgumentException(
          s"PROFILE_TEST_DB_PORT must be a valid TCP port: '$portValue'"
        )
      }
    val password = environment.get("PROFILE_TEST_DB_PASSWORD").filter(_.nonEmpty)
      .getOrElse {
        throw new IllegalStateException(
          "PROFILE_TEST_DB_PASSWORD must be set to run PostgreSQL integration tests"
        )
      }
    DatabaseConfig(
      host = environment.getOrElse("PROFILE_TEST_DB_HOST", "localhost"),
      port = port,
      name = environment.getOrElse("PROFILE_TEST_DB_NAME", "profiledb_test"),
      username = environment.getOrElse("PROFILE_TEST_DB_USER", "profileuser_test"),
      password = password
    )

  private def withRepository[A](
    test: (
      DoobieProfileRepository[IO],
      doobie.util.transactor.Transactor[IO]
    ) => IO[A]
  ): IO[A] =
    DatabaseMigrations.migrate[IO](config) *>
      DatabaseTransactor.create[IO](config).use { transactor =>
        test(new DoobieProfileRepository[IO](transactor), transactor)
      }

  private def unusedPort: IO[Int] =
    IO.blocking {
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }

  test("Flyway migration is repeatable and creates the profile table"):
    for
      first <- DatabaseMigrations.migrate[IO](config).attempt
      second <- DatabaseMigrations.migrate[IO](config).attempt
      tableExists <- DatabaseTransactor.create[IO](config).use { transactor =>
        sql"""
          SELECT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'profiles'
          )
        """.query[Boolean].unique.transact(transactor)
      }
    yield
      assertEquals(first, Right(()))
      assertEquals(second, Right(()))
      assert(tableExists)

  test("transactor connects and executes a query"):
    DatabaseTransactor.create[IO](config).use { transactor =>
      sql"SELECT 1".query[Int].unique.transact(transactor).map(value =>
        assertEquals(value, 1)
      )
    }

  test("application resource starts after migrations and serves database readiness"):
    for
      httpPort <- unusedPort
      grpcPort <- unusedPort
      host = Host.fromString("127.0.0.1").get
      http = Port.fromInt(httpPort).get
      serviceConfig = ProfileServiceConfig(
        host,
        http,
        grpcPort,
        "test",
        config,
        JwtConfig("test-only-secret-that-is-at-least-48-bytes-long!")
      )
      result <- DwpProfileServiceServer
        .serverResource[IO](serviceConfig)
        .use { _ =>
          EmberClientBuilder.default[IO].build.use { client =>
            val request = Request[IO](
              Method.GET,
              Uri.unsafeFromString(s"http://127.0.0.1:$httpPort/health")
            )
            client.run(request).use(response => IO.pure(response.status))
          }
        }
    yield assertEquals(result, Status.Ok)

  test("repository creates defaults and reads private and public profile projections"):
    val userId = UUID.randomUUID()
    withRepository { (repository, _) =>
      for
        created <- repository.createDefaultProfile(userId)
        privateProfile <- repository.findByUserId(userId)
        publicProfile <- repository.findPublicByUserId(userId)
      yield
        assertEquals(created.userId, userId)
        assertEquals(created.preferences, Json.obj())
        assertEquals(privateProfile, Some(created))
        assertEquals(publicProfile.map(_.userId), Some(userId))
        assert(publicProfile.exists(_.createdAt == created.createdAt))
    }

  test("idempotent profile creation preserves customized values"):
    val userId = UUID.randomUUID()
    withRepository { (repository, _) =>
      for
        _ <- repository.createDefaultProfile(userId)
        updated <- repository.updateProfile(
          userId,
          ProfileUpdate(
            Some("Customized"),
            Some("Keep this"),
            Some("https://example.test/avatar.png"),
            Json.obj("theme" -> Json.fromString("dark")),
            Json.obj("site" -> Json.fromString("https://example.test"))
          )
        )
        retried <- repository.createDefaultProfile(userId)
      yield
        assertEquals(updated.map(_.displayName), Some(Some("Customized")))
        assertEquals(retried.displayName, Some("Customized"))
        assertEquals(retried.bio, Some("Keep this"))
        assertEquals(retried.preferences, Json.obj("theme" -> Json.fromString("dark")))
        assertEquals(retried.socialLinks, Json.obj("site" -> Json.fromString("https://example.test")))
    }

  test("repository update replaces mutable fields and returns None for missing profiles"):
    val userId = UUID.randomUUID()
    val missingId = UUID.randomUUID()
    withRepository { (repository, _) =>
      for
        _ <- repository.createDefaultProfile(userId)
        updated <- repository.updateProfile(
          userId,
          ProfileUpdate(None, Some("Updated bio"), None, Json.obj(), Json.obj())
        )
        missing <- repository.updateProfile(
          missingId,
          ProfileUpdate(None, None, None, Json.obj(), Json.obj())
        )
        privateProfile <- repository.findByUserId(userId)
        absent <- repository.findByUserId(missingId)
      yield
        assertEquals(updated.map(_.displayName), Some(None))
        assertEquals(updated.map(_.bio), Some(Some("Updated bio")))
        assertEquals(missing, None)
        assert(privateProfile.exists(_.updatedAt == updated.get.updatedAt))
        assertEquals(absent, None)
    }

  test("database rejects non-object JSON documents"):
    val userId = UUID.randomUUID()
    withRepository { (_, transactor) =>
      sql"""
        INSERT INTO profiles (user_id, preferences, social_links)
        VALUES ($userId, ${Json.arr(Json.fromInt(1))}, ${Json.obj()})
      """.update.run
        .transact(transactor)
        .attempt
        .map(result => assert(result.isLeft))
    }
