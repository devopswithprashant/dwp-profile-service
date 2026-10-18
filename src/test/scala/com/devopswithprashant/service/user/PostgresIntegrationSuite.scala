package com.devopswithprashant.service.user

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
import org.testcontainers.containers.PostgreSQLContainer

class PostgresIntegrationSuite extends CatsEffectSuite:

  private val postgres = new PostgreSQLContainer("postgres:15-alpine")

  override def beforeAll(): Unit =
    super.beforeAll()
    postgres.start()

  override def afterAll(): Unit =
    postgres.stop()
    super.afterAll()

  private def config =
    DatabaseConfig(
      host = postgres.getHost,
      port = postgres.getFirstMappedPort,
      name = postgres.getDatabaseName,
      username = postgres.getUsername,
      password = postgres.getPassword
    )

  private def withRepository[A](
    test: (
      DoobieUserProfileRepository[IO],
      doobie.util.transactor.Transactor[IO]
    ) => IO[A]
  ): IO[A] =
    DatabaseMigrations.migrate[IO](config) *>
      DatabaseTransactor.create[IO](config).use { transactor =>
        test(new DoobieUserProfileRepository[IO](transactor), transactor)
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
            WHERE table_schema = 'public' AND table_name = 'user_profiles'
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
      serviceConfig = UserServiceConfig(
        host,
        http,
        grpcPort,
        "test",
        config,
        JwtConfig("test-only-secret-that-is-at-least-48-bytes-long!")
      )
      result <- DwpuserserviceServer
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
          UserProfileUpdate(
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
          UserProfileUpdate(None, Some("Updated bio"), None, Json.obj(), Json.obj())
        )
        missing <- repository.updateProfile(
          missingId,
          UserProfileUpdate(None, None, None, Json.obj(), Json.obj())
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
        INSERT INTO user_profiles (user_id, preferences, social_links)
        VALUES ($userId, ${Json.arr(Json.fromInt(1))}, ${Json.obj()})
      """.update.run
        .transact(transactor)
        .attempt
        .map(result => assert(result.isLeft))
    }
