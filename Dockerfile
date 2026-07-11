FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=builder /workspace/build/libs/*.jar app.jar
USER spring:spring

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=12 CMD wget -q -O /dev/null 'http://127.0.0.1:8080/api/auth/check-duplicates?email=health%40local&username=health' || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
