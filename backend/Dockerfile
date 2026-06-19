# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
# Spring Boot 4.1 / Java 25. Render has no native Java runtime, so we build a
# self-contained image. The Maven image already bundles a JDK + Maven, so no
# wrapper download is needed at build time.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# Cache dependencies first for faster rebuilds.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

# finalName defaults to ${artifactId}-${version} -> glr-hr-backend-0.1.0.jar
COPY --from=build /app/target/glr-hr-backend-0.1.0.jar app.jar

# Render injects PORT; application.yml binds server.port to ${PORT:...}.
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
