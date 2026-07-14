# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
# Spring Boot / Java 21 (LTS). Render has no native Java runtime, so we build a
# self-contained image. The Maven image already bundles a JDK + Maven, so no
# wrapper download is needed at build time.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first for faster rebuilds.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# finalName defaults to ${artifactId}-${version} -> glr-hr-backend-0.1.0.jar
COPY --from=build /app/target/glr-hr-backend-0.1.0.jar app.jar

# Drop root: run as an unprivileged user. The uploads path is deliberately outside the
# application directory so operators can mount persistent on-prem storage there.
RUN useradd -r -u 10001 appuser \
    && mkdir -p /var/lib/glr-hr/uploads \
    && chown -R appuser /var/lib/glr-hr
USER 10001

# Render injects PORT; application.yml binds server.port to ${PORT:...}.
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
