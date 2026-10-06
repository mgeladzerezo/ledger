# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
# Tests need Docker (Testcontainers) and run in CI, not inside the image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home ledger
WORKDIR /app
COPY --from=build /build/target/ledger-1.0.0.jar /app/ledger.jar
USER ledger
EXPOSE 8206
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/ledger.jar"]
