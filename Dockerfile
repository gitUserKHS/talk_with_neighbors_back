ARG BUILDPLATFORM
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-jammy@sha256:400014962ad7224461f945bb1cc3d7d5a1927ce15b8245b72d9cedcda554cd2a AS builder
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-jammy@sha256:e17d77fb030dd4b642dc078d048a5fb9efcb3676ee20305d905949105a6ccd5a
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --home-dir /app --shell /usr/sbin/nologin spring \
    && mkdir -p /app/uploads \
    && chown -R spring:spring /app/uploads
COPY --from=builder /workspace/build/libs/*.jar app.jar
USER spring:spring

EXPOSE 8080
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=12 CMD wget -q -O /dev/null 'http://127.0.0.1:8080/actuator/health/liveness' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
