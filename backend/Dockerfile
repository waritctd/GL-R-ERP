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
# fontconfig provides fc-cache so bundled fonts get registered.
RUN apt-get update && \
    apt-get install -y --no-install-recommends libreoffice-calc fontconfig && \
    rm -rf /var/lib/apt/lists/*

# Install bundled fonts used by Excel templates and LibreOffice PDF rendering.
# These are proprietary (Angsana/Browallia/Cordia New, Tahoma, Arial, Calibri, Cambria) and MUST
# NEVER be committed to this repo — backend/fonts/ is git-ignored and must be populated with the
# real .ttf/.ttc files by whatever runs `docker build` (fetched from private storage you control),
# not by git. See backend/fonts/README.md for how to supply them.
COPY fonts/ /usr/local/share/fonts/glr/

# Rebuild the system font cache and fail the build if registration fails. Checked individually
# (not one combined grep) so a single missing family fails loudly instead of being masked by the
# others still matching — these are exactly the families the three live templates reference (see
# backend/fonts/README.md).
RUN fc-cache -f -v && \
    for f in "Arial" "Calibri" "Cambria" "Tahoma" "Angsana New" "Browallia New" "BrowalliaUPC" "Cordia New"; do \
      fc-list | grep -qi "$f" || { echo "Missing font: $f" >&2; exit 1; }; \
    done

# finalName defaults to ${artifactId}-${version} -> glr-hr-backend-0.1.0.jar
COPY --from=build /app/target/glr-hr-backend-0.1.0.jar app.jar

# Drop root: run as an unprivileged user (least privilege / container hardening, issue #27).
RUN useradd -r -u 10001 appuser
USER 10001

# Render injects PORT; application.yml binds server.port to ${PORT:...}.
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
