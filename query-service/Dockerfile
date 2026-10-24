# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build stage
# Deliberately byte-identical across all eight service Dockerfiles. Docker keys its layer
# cache on the instruction plus the context, so this stage is built once and the other seven
# images reuse it rather than compiling the project eight times.
#
# The build context is the repository root, not this directory: Gradle needs the whole
# multi-module project, and :query-service depends on :common.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src

COPY . .

# The wrapper, not the base image's Gradle, so the version stays pinned by the repository
# (8.8 - the project does not build on 9.x). --no-daemon because the daemon would outlive
# nothing useful in a single-shot container build.
RUN chmod +x ./gradlew && ./gradlew --no-daemon bootJar -x test

# -------------------------------------------------------------- runtime stage
# JRE only. The JDK, the Gradle distribution and the build cache stay in the stage above and
# never reach the image, which is the difference between ~200MB and ~900MB per service across
# eight services on a small VM.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# curl is here solely so the container healthcheck can call /api/health. Without it the only
# readiness signal compose has is "the process started", which for a Spring Boot service is
# roughly a minute before it can actually serve anything - and depends_on would let the rest
# of the stack start against a service that is not listening yet.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Nothing here needs root, and a compromised service should not be able to write to its own
# image.
RUN useradd --system --create-home --uid 10001 spendsense
USER spendsense

# Only the bootJar exists in build/libs: the root build file disables the plain jar for
# service modules, so this glob cannot pick the wrong one.
COPY --from=build --chown=spendsense:spendsense /src/query-service/build/libs/*.jar app.jar

# The JVM must size its heap from the CONTAINER limit, not the host's RAM. Without this a
# 320MB container on a 4GB machine sizes a ~1GB heap and is killed under load rather than
# collecting garbage. 75% leaves room for metaspace, thread stacks and direct buffers, which
# live outside the heap but inside the limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

EXPOSE 8085

# exec, so the JVM is PID 1 and receives SIGTERM directly - otherwise the shell swallows it
# and every stop waits out the full 10s kill timeout.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]