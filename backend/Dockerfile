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

# Rebuild the font cache, then PROVE the required families are actually usable. A missing font is
# now a BUILD FAILURE, not a warning (#666).
#
# It used to warn and continue when backend/fonts/ was empty. That looked like kind graceful
# degradation and was really a silent defect channel: Render built from a fresh clone where the
# folder is always empty, so the showcase shipped substitute fonts for months with nothing but a
# build-log line nobody reads. backend/fonts/README.md meanwhile claimed the build already failed
# in this case — the two disagreed, which is a large part of why it went unnoticed. They agree now.
#
# Escape hatch, deliberately explicit and off by default: --build-arg ALLOW_MISSING_FONTS=true for
# a knowingly fontless build (a local smoke test, say). Anything that reaches a user should never
# set it.
#
# TWO checks, because the obvious one is not sufficient:
#
#   1. fc-list  — is a font by this NAME registered? Catches a truncated or partial font fetch.
#   2. fc-match — does fontconfig actually RESOLVE this family to itself, or silently fall back?
#      This is the check that was missing, and the only one that detects substitution. fc-match
#      never fails: asked for a font it does not have, it cheerfully returns whatever it would
#      substitute. So "Angsana New" resolving to DejaVu Sans is exactly the production defect,
#      and check 1 alone cannot see it — LibreOffice makes the same substitution and the wider
#      glyphs overflow the certification badges on customer-facing documents.
ARG ALLOW_MISSING_FONTS=false
RUN fc-cache -f -v && \
    if ! find /usr/local/share/fonts/glr -type f \( -iname '*.ttf' -o -iname '*.ttc' -o -iname '*.otf' \) -print -quit | grep -q .; then \
      if [ "$ALLOW_MISSING_FONTS" = "true" ]; then \
        echo "WARNING: ALLOW_MISSING_FONTS=true and backend/fonts/ is empty — this image renders customer documents in SUBSTITUTE fonts. Never deploy it." >&2; \
      else \
        echo "ERROR: backend/fonts/ is empty. The licensed fonts must be fetched into it before docker build — see backend/fonts/README.md. Override only for a throwaway build with --build-arg ALLOW_MISSING_FONTS=true." >&2; \
        exit 1; \
      fi; \
    else \
      for f in "Arial" "Calibri" "Cambria" "Tahoma" "Angsana New" "Browallia New" "BrowalliaUPC" "Cordia New"; do \
        fc-list | grep -qi "$f" || { echo "ERROR: font not registered: $f" >&2; exit 1; }; \
        resolved=$(fc-match "$f" family 2>/dev/null); \
        case "$resolved" in \
          *"$f"*) ;; \
          *) echo "ERROR: '$f' resolves to '$resolved' — fontconfig is SUBSTITUTING it. Customer PDFs would render wrong. See #666." >&2; exit 1;; \
        esac; \
      done; \
      echo "All 8 required font families registered and resolving to themselves."; \
    fi

# finalName defaults to ${artifactId}-${version} -> glr-hr-backend-0.1.0.jar
COPY --from=build /app/target/glr-hr-backend-0.1.0.jar app.jar

# Drop root: run as an unprivileged user (least privilege / container hardening, issue #27).
RUN useradd -r -u 10001 appuser

# Make the default attachment upload dir writable by the non-root runtime user. app.uploads-dir
# defaults to ./uploads -> /app/uploads (WORKDIR is /app, which is root-owned), so without this the
# JVM running as UID 10001 hits AccessDeniedException on Files.createDirectories the first time any
# file is uploaded (commission invoice, deposit notice, ticket attachment) -> generic 500. Creating
# and chowning it here makes the default work under least-privilege regardless of APP_UPLOADS_DIR.
# NOTE: this dir is on the container's EPHEMERAL filesystem — for durable retention in production,
# also set APP_UPLOADS_DIR to a mounted persistent disk path (no Render disk is defined today).
RUN mkdir -p /app/uploads && chown 10001:10001 /app/uploads

USER 10001

# Render injects PORT; application.yml binds server.port to ${PORT:...}.
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
