# Server image for Render (CLAUDE.md §8). Builds only :server and :core — see the
# WYR_SERVER_ONLY switch in settings.gradle.kts for why the app modules are excluded.
#
# Zulu 21 rather than another JDK: gradle/gradle-daemon-jvm.properties pins the daemon toolchain
# to Azul 21, and matching it here avoids Gradle downloading a second JVM inside the build.

FROM azul/zulu-openjdk:21 AS build

ENV WYR_SERVER_ONLY=1 \
    GRADLE_OPTS="-Dorg.gradle.daemon=false"

WORKDIR /build

# Wrapper and build config first, so dependency resolution caches across source-only changes.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./

COPY core core
COPY server server

RUN chmod +x gradlew && \
    ./gradlew --no-daemon --no-configuration-cache :server:buildFatJar

FROM azul/zulu-openjdk:21-jre-headless AS runtime

WORKDIR /app

# Never run as root in the deployed container.
RUN useradd --system --create-home --shell /usr/sbin/nologin wyr
USER wyr

COPY --from=build /build/server/build/libs/*-all.jar app.jar

# Render injects PORT; ServerConfig reads it and falls back to 8080 locally.
EXPOSE 8080

# UseContainerSupport lets the JVM size the heap from the container's cgroup limit rather than
# the host's memory, which matters on Render's small instances.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
