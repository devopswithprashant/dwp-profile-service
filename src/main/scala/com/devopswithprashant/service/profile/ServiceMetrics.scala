package com.devopswithprashant.service.profile

import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import io.prometheus.client.exporter.common.TextFormat
import java.io.StringWriter

final class ServiceMetrics(
  val registry: CollectorRegistry = new CollectorRegistry()
):

  private val httpRequests =
    Counter
      .build()
      .name("http_server_requests_total")
      .help("Total HTTP requests handled by the Profile Service.")
      .labelNames("method", "route", "status")
      .register(registry)

  private val httpRequestDuration =
    Histogram
      .build()
      .name("http_server_request_duration_seconds")
      .help("HTTP request duration in seconds.")
      .labelNames("method", "route")
      .buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
      .register(registry)

  private val grpcRequests =
    Counter
      .build()
      .name("grpc_server_requests_total")
      .help("Total Profile Service gRPC requests.")
      .labelNames("method", "status")
      .register(registry)

  private val databaseUp =
    Gauge
      .build()
      .name("database_health")
      .help("Whether the configured PostgreSQL database is reachable (1) or not (0).")
      .register(registry)

  def recordHttp(
    method: String,
    route: String,
    status: Int,
    durationSeconds: Double
  ): Unit =
    httpRequests.labels(method, route, status.toString).inc()
    httpRequestDuration.labels(method, route).observe(durationSeconds)

  def recordGrpc(method: String, status: String): Unit =
    grpcRequests.labels(method, status).inc()

  def recordDatabaseHealth(isUp: Boolean): Unit =
    databaseUp.set(if isUp then 1.0 else 0.0)

  def renderPrometheus: String =
    val writer = new StringWriter()
    TextFormat.write004(writer, registry.metricFamilySamples())
    writer.toString
