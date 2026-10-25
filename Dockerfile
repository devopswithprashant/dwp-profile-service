FROM sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_1.13.0_3.3.8 AS build

WORKDIR /build

COPY build.sbt ./
COPY project ./project
RUN sbt update

COPY src ./src
RUN sbt assembly

RUN jlink \
    --add-modules java.base,java.logging,java.management,java.naming,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /opt/jre

FROM gcr.io/distroless/base-debian12:nonroot AS runtime

WORKDIR /app
COPY --from=build --chown=nonroot:nonroot /opt/jre /opt/jre
COPY --from=build /build/target/scala-3.3.8/dwp-profile-service.jar /app/service.jar

EXPOSE 8080 9090

ENTRYPOINT ["/opt/jre/bin/java"]
CMD ["-XX:MaxRAMPercentage=75.0", "-jar", "/app/service.jar"]
