# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
# Spring Boot 3.5 / Java 21 (LTS). Render has no native Java runtime, so we build a
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

# Install LibreOffice Calc for XLS→PDF conversion (soffice --headless).
# libreoffice-calc is the spreadsheet-only subset (~150 MB); no GUI needed.
# fontconfig provides fc-cache so bundled Thai fonts get registered.
RUN apt-get update && \
    apt-get install -y --no-install-recommends libreoffice-calc fontconfig && \
    rm -rf /var/lib/apt/lists/*

# Install the Windows Thai fonts (Angsana/Browallia/Cordia New) the templates use, so
# LibreOffice renders the PDF exactly like Excel. The .ttf files are git-ignored and must
# be present in backend/fonts/ at build time (see backend/fonts/README.md). The COPY of the
# README guarantees the layer exists even when no fonts are supplied (fc-cache then no-ops).
COPY fonts/ /usr/share/fonts/truetype/thai/
RUN fc-cache -f /usr/share/fonts/truetype/thai/ || true

# finalName defaults to ${artifactId}-${version} -> glr-hr-backend-0.1.0.jar
COPY --from=build /app/target/glr-hr-backend-0.1.0.jar app.jar

# Drop root: run as an unprivileged user (least privilege / container hardening, issue #27).
RUN useradd -r -u 10001 appuser
USER 10001

# Render injects PORT; application.yml binds server.port to ${PORT:...}.
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
