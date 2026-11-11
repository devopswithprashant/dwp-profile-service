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

FROM debian:bookworm-slim AS openssl-runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends libssl3 \
    && version="$(dpkg-query -W -f='${Version}' libssl3)" \
    && dpkg --compare-versions "$version" ge "3.0.22-1~deb12u2" \
    && mkdir -p /out/var/lib/dpkg/status.d \
    && dpkg-query -L libssl3 | while IFS= read -r path; do \
        case "$path" in \
            /usr/lib/*) \
                if [ -f "$path" ] || [ -L "$path" ]; then \
                    mkdir -p "/out$(dirname "$path")" \
                    && cp -a "$path" "/out$path"; \
                fi \
                ;; \
        esac; \
    done \
    && dpkg-query -s libssl3 > /out/var/lib/dpkg/status.d/libssl3 \
    && md5sums="$(find /var/lib/dpkg/info -maxdepth 1 -name 'libssl3*.md5sums' -print -quit)" \
    && if [ -n "$md5sums" ]; then cp "$md5sums" /out/var/lib/dpkg/status.d/libssl3.md5sums; fi \
    && rm -rf /var/lib/apt/lists/*

FROM gcr.io/distroless/base-debian12:nonroot AS runtime

WORKDIR /app
COPY --from=openssl-runtime /out/ /
COPY --from=build --chown=nonroot:nonroot /opt/jre /opt/jre
COPY --from=build /build/target/scala-3.3.8/dwp-profile-service.jar /app/service.jar

EXPOSE 8080 9090

ENTRYPOINT ["/opt/jre/bin/java"]
CMD ["-XX:MaxRAMPercentage=75.0", "-jar", "/app/service.jar"]
