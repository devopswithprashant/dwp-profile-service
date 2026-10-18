package com.devopswithprashant.service.user

import cats.effect.IO
import cats.syntax.all.*
import com.devopswithprashant.user.v1.user_profile.CreateDefaultProfileRequest
import io.grpc.{Metadata, Status, StatusRuntimeException}
import java.sql.SQLException
import java.util.UUID
import munit.CatsEffectSuite

class UserProfileGrpcServiceSuite extends CatsEffectSuite:

  import TestSupport.*

  private def service(
    repository: UserProfileRepository[IO]
  ): UserProfileGrpcService[IO] =
    new UserProfileGrpcService[IO](new UserProfileService[IO](repository))

  private def failureStatus(error: Throwable): Status.Code =
    Status.fromThrowable(error).getCode

  test("creates a profile and returns the canonical user UUID"):
    val userId = UUID.randomUUID()
    for
      repo <- repository()
      response <- service(repo).createDefaultProfile(
        CreateDefaultProfileRequest(userId = userId.toString),
        new Metadata()
      )
      stored <- repo.findByUserId(userId)
    yield
      assertEquals(response.userId, userId.toString)
      assert(stored.isDefined)

  test("rejects malformed and noncanonical UUIDs with INVALID_ARGUMENT"):
    val requests = List("not-a-uuid", "00000000000000000000000000000000")
    for
      repo <- repository()
      statuses <- requests.traverse[IO, Option[Status.Code]] { userId =>
        service(repo)
          .createDefaultProfile(
            CreateDefaultProfileRequest(userId = userId),
            new Metadata()
          )
          .attempt
          .map(_.left.toOption.map(failureStatus))
      }
    yield assertEquals(statuses, List(Some(Status.Code.INVALID_ARGUMENT), Some(Status.Code.INVALID_ARGUMENT)))

  test("maps SQL exceptions to UNAVAILABLE"):
    val userId = UUID.randomUUID()
    for
      repo <- repository(createFailure = Some(new SQLException("database offline")))
      result <- service(repo)
        .createDefaultProfile(
          CreateDefaultProfileRequest(userId = userId.toString),
          new Metadata()
        )
        .attempt
    yield
      assertEquals(result.left.toOption.map(failureStatus), Some(Status.Code.UNAVAILABLE))
      assert(result.left.toOption.exists(_.isInstanceOf[StatusRuntimeException]))

  test("maps other non-fatal failures to INTERNAL"):
    val userId = UUID.randomUUID()
    for
      repo <- repository(createFailure = Some(new IllegalStateException("unexpected")))
      result <- service(repo)
        .createDefaultProfile(
          CreateDefaultProfileRequest(userId = userId.toString),
          new Metadata()
        )
        .attempt
    yield assertEquals(result.left.toOption.map(failureStatus), Some(Status.Code.INTERNAL))
