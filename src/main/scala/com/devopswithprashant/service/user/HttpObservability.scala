package com.devopswithprashant.service.user

import cats.effect.Async
import cats.syntax.all.*
import cats.data.Kleisli
import net.logstash.logback.marker.Markers
import org.http4s.{HttpApp, Request}
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*

object HttpObservability:

  private val logger = LoggerFactory.getLogger("http.access")

  def instrument[F[_]: Async](
    app: HttpApp[F],
    metrics: ServiceMetrics
  ): HttpApp[F] =
    // Keeps the wrapper at the HttpApp boundary so logging never reads or
    // buffers authorization headers or request/response bodies.
    Kleisli { (request: Request[F]) =>
      for
        started <- Async[F].monotonic
        result <- app.run(request).attempt
        finished <- Async[F].monotonic
        elapsed = (finished - started).toNanos.toDouble / 1_000_000_000d
        route = routeLabel(request)
        method = methodLabel(request.method.name)
        status = result.fold(_ => 500, _.status.code)
        _ <- Async[F].delay {
          metrics.recordHttp(method, route, status, elapsed)
          val errorFields =
            result.left.toOption
              .map(error => Map("error_type" -> error.getClass.getSimpleName))
              .getOrElse(Map.empty)
          val marker = Markers.appendEntries(
            Map[String, AnyRef](
              "event" -> (if status >= 500 then "http_request_failed" else "http_request"),
              "method" -> method,
              "route" -> route,
              "status" -> Integer.valueOf(status),
              "duration_ms" -> java.lang.Double.valueOf(elapsed * 1000d)
            ).concat(errorFields).asJava
          )
          if status >= 500 then logger.error(marker, "HTTP request completed with server error")
          else logger.info(marker, "HTTP request completed")
        }
        response <- result.liftTo[F]
      yield response
    }

  private def methodLabel(method: String): String =
    method match
      case "GET" | "PUT" | "POST" | "DELETE" | "PATCH" | "OPTIONS" | "HEAD" =>
        method
      case _ => "OTHER"

  private def routeLabel(request: Request[?]): String =
    val path = request.uri.path.renderString
    path match
      case "/health" => "/health"
      case "/health/live" => "/health/live"
      case "/metrics" => "/metrics"
      case "/api/v1/users/me" => "/api/v1/users/me"
      case value if value.startsWith("/api/v1/users/") =>
        "/api/v1/users/{userId}"
      case _ => "unmatched"
