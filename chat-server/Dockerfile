# syntax=docker/dockerfile:1
#
# The Tetherless relay.
#
# Build from the repository root, not from this directory, because the build stage needs
# core-shared and the Gradle wrapper:
#
#   docker build -f chat-server/Dockerfile -t tetherless-relay:1.0.0 \
#     --build-arg GIT_COMMIT="$(git rev-parse --short HEAD)" \
#     --build-arg GIT_COMMIT_DATE="$(git log -1 --format=%cd --date=iso-strict)" .
#
# The image contains no certificate. That is deliberate: the relay's private key is mounted at run
# time, and an image with a key baked in is a key published to whoever can pull the image.

# ---------------------------------------------------------------------------- build

FROM eclipse-temurin:21-jdk AS build

WORKDIR /src

# The wrapper alone first, so the Gradle distribution download is cached in its own layer and does
# not repeat every time a source file changes.
COPY gradlew ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version > /dev/null

COPY gradle.properties settings.gradle build.gradle ./
COPY config config

# gradle.properties pins a Windows JDK path for local development, which obviously does not exist
# here. Dropping the line lets Gradle use the image's JDK.
RUN sed -i '/org\.gradle\.java\.home/d' gradle.properties

COPY core-shared core-shared
COPY chat-server chat-server

# settings.gradle skips modules that are not in the build context, so no Android toolchain and no
# desktop sources are needed to compile a server.
#
# -PreleaseBuild is required, not decorative: it stamps channel=release, which is what makes
# ServerConfig refuse to serve the development keystore. The commit values are passed in because
# .dockerignore keeps .git out of the context.
ARG GIT_COMMIT=unknown
ARG GIT_COMMIT_DATE=unknown
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :chat-server:fatJar -PreleaseBuild \
        -PgitCommit="$GIT_COMMIT" -PgitCommitDate="$GIT_COMMIT_DATE"

# --------------------------------------------------------------------------- runtime

FROM eclipse-temurin:21-jre AS runtime

# curl is only here for the health check; nothing else in the image needs it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# An unprivileged account with no shell and no home to write to. The relay needs neither.
RUN groupadd --system relay \
    && useradd --system --gid relay --no-create-home --shell /usr/sbin/nologin relay

WORKDIR /opt/tetherless
COPY --from=build /src/chat-server/build/libs/tetherless-relay-*.jar relay.jar

# Defaults; override with -e or compose. KEYSTORE_PATH has none on purpose - the relay refuses to
# start without one rather than falling back to a keypair anyone can regenerate.
# Logs go to stdout where the container runtime collects them; see logback-container.xml. The
# default config writes a rolling file, which an unprivileged process on a read-only root
# filesystem cannot create.
ENV PORT=8080 \
    LOG_LEVEL=info \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dlogback.configurationFile=logback-container.xml"

USER relay

# The relay itself. The metrics endpoint is PORT+1 and is deliberately not exposed here: it is
# unauthenticated plaintext HTTP and has no business on a public interface.
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:$((PORT + 1))/metrics" > /dev/null || exit 1

# Run through sh so JAVA_OPTS expands, with exec so the JVM keeps PID 1 and receives SIGTERM.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /opt/tetherless/relay.jar"]
