ARG BUILDPLATFORM
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-jammy@sha256:0348e7b24ad4479cf35927b750671bb4b78465c303003b08536f6f2fa6f180cd AS builder
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:25-jre-jammy@sha256:b8ba5fca9d88b6ecc3a46c8e75b744f84aca9a9d08587901b5ab480baf641ab5
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
