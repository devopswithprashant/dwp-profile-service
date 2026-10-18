package com.devopswithprashant.service.user

import cats.effect.IO
import com.devopswithprashant.user.v1.user_profile.{
  CreateDefaultProfileRequest,
  UserProfileServiceGrpc
}
import io.grpc.{ManagedChannel, ManagedChannelBuilder, Status, StatusRuntimeException}
import java.net.ServerSocket
import java.util.UUID
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import TestSupport.*

class UserProfileGrpcServerSuite extends CatsEffectSuite:

  private def unusedPort: IO[Int] =
    IO.blocking {
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }

  private def channel(port: Int): IO[ManagedChannel] =
    IO.blocking {
      ManagedChannelBuilder
        .forAddress("127.0.0.1", port)
        .usePlaintext()
        .build()
    }

  private def closeChannel(channel: ManagedChannel): IO[Unit] =
    IO.blocking {
      channel.shutdownNow()
      channel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
      ()
    }

  test("binds the gRPC server and serves valid and invalid provisioning calls"):
    for
      port <- unusedPort
      repo <- repository()
      service = new UserProfileService[IO](repo)
      result <- UserProfileGrpcServer
        .resource[IO](port, service, new ServiceMetrics())
        .use { _ =>
          channel(port).bracket { grpcChannel =>
            IO.blocking {
              val stub = new UserProfileServiceGrpc.UserProfileServiceBlockingStub(grpcChannel)
              val userId = UUID.randomUUID()
              val response =
                stub.createDefaultProfile(
                  CreateDefaultProfileRequest(userId = userId.toString)
                )
              assertEquals(response.userId, userId.toString)
              val invalid =
                intercept[StatusRuntimeException] {
                  stub.createDefaultProfile(
                    CreateDefaultProfileRequest(userId = "invalid")
                  )
                }
              assertEquals(Status.fromThrowable(invalid).getCode, Status.Code.INVALID_ARGUMENT)
            }
          }(closeChannel)
        }
    yield assertEquals(result, ())
