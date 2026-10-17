# syntax=docker/dockerfile:1

# Packages the boot jar that `./gradlew bootJar` built and CI tested. The image only runs it.
# The JRE matches the JDK pinned in mise.toml, so production runs the build CI tested on.
FROM eclipse-temurin:25.0.4.1_1-jre-resolute@sha256:41d9263c0d5b2a74d89af6e29e73b04298bc65e3cb705d03e9e2271ef49fef84 AS base

# Splits the jar into layers that change at different rates: a release that only changes our code
# ships a small layer, and the libraries stay cached on the server.
FROM base AS extract
WORKDIR /build
COPY build/libs/vienna-pulse.jar .
RUN java -Djarmode=tools -jar vienna-pulse.jar extract --layers --destination extracted

FROM base
# A fixed, unprivileged user. The name gives Java a home directory and a user name.
RUN groupadd --system --gid 10001 pulse \
    && useradd --system --uid 10001 --gid pulse --no-create-home --home-dir /app pulse
WORKDIR /app
COPY --link --from=extract /build/extracted/dependencies/ ./
COPY --link --from=extract /build/extracted/spring-boot-loader/ ./
COPY --link --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --link --from=extract /build/extracted/application/ ./
USER 10001:10001

EXPOSE 8080
# The heap takes a share of the container's memory limit, not of the host's memory. The rest is
# for metaspace, thread stacks, code cache and buffers. Out of memory, the JVM exits and the
# container restarts, instead of limping on with half its threads dead.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "vienna-pulse.jar"]

# Ready means the database answers too. The image has bash but no curl, so bash speaks HTTP.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --start-interval=2s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
