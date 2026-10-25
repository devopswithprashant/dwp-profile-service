package com.devopswithprashant.service.profile

import cats.effect.IO
import cats.syntax.all.*
import com.devopswithprashant.profile.v1.profile.CreateDefaultProfileRequest
import io.grpc.{Metadata, Status, StatusRuntimeException}
import java.sql.SQLException
import java.util.UUID
import munit.CatsEffectSuite

class ProfileGrpcServiceSuite extends CatsEffectSuite:

  import TestSupport.*

  private def service(
    repository: ProfileRepository[IO],
    metrics: ServiceMetrics = new ServiceMetrics()
  ): ProfileGrpcService[IO] =
    new ProfileGrpcService[IO](new ProfileService[IO](repository), metrics)

  private def failureStatus(error: Throwable): Status.Code =
    Status.fromThrowable(error).getCode

  test("creates a profile and returns the canonical user UUID"):
    val userId = UUID.randomUUID()
    val metrics = new ServiceMetrics()
    for
      repo <- repository()
      response <- service(repo, metrics).createDefaultProfile(
        CreateDefaultProfileRequest(userId = userId.toString),
        new Metadata()
      )
      stored <- repo.findByUserId(userId)
    yield
      assertEquals(response.userId, userId.toString)
      assert(stored.isDefined)
      assert(
        metrics.renderPrometheus.linesIterator.exists(line =>
          line.startsWith("grpc_server_requests_total{") &&
            line.contains("""status="OK"""") &&
            line.endsWith(" 1.0")
        )
      )

  test("rejects malformed and noncanonical UUIDs with INVALID_ARGUMENT"):
    val requests = List("not-a-uuid", "00000000000000000000000000000000")
    val metrics = new ServiceMetrics()
    for
      repo <- repository()
      statuses <- requests.traverse[IO, Option[Status.Code]] { userId =>
        service(repo, metrics)
          .createDefaultProfile(
            CreateDefaultProfileRequest(userId = userId),
            new Metadata()
          )
          .attempt
          .map(_.left.toOption.map(failureStatus))
      }
    yield
      assertEquals(statuses, List(Some(Status.Code.INVALID_ARGUMENT), Some(Status.Code.INVALID_ARGUMENT)))
      assert(
        metrics.renderPrometheus.linesIterator.exists(line =>
          line.startsWith("grpc_server_requests_total{") &&
            line.contains("""status="INVALID_ARGUMENT"""") &&
            line.endsWith(" 2.0")
        )
      )

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
