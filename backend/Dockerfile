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

# Install LibreOffice Calc for XLS→PDF conversion (soffice --headless), plus a free Thai font
# family as a readability-of-last-resort fallback (UAT #3 — see the big comment block below).
# libreoffice-calc is the spreadsheet-only subset (~150 MB); no GUI needed.
# fontconfig provides fc-cache so bundled fonts get registered.
# fonts-thai-tlwg ships Kinnari/Garuda/Umpush/Norasi/Waree — metric-compatible substitutes for the
# classic MS Thai font names. Installing it means even a totally fontless build (see below) still
# renders real Thai glyphs instead of hollow .notdef boxes. It does NOT replace the licensed fonts:
# only they have the exact metrics the templates were laid out against — see backend/fonts/README.md.
RUN apt-get update && \
    apt-get install -y --no-install-recommends libreoffice-calc fontconfig fonts-thai-tlwg && \
    rm -rf /var/lib/apt/lists/*

# fonts-thai-tlwg's own fontconfig rules already cover three of the templates' four Thai family
# names — verified empirically in an isolated container (fontconfig + fonts-thai-tlwg only, no
# licensed fonts): Angsana New -> Kinnari, Browallia New and BrowalliaUPC -> Garuda, Cordia New ->
# Umpush. It does NOT cover two faces the templates also reference: Tahoma (used in
# quotation_template.xls) and "Lucida Grande" (a macOS font a template author left in
# remaining_invoice_template.xls — unused by any Thai cell today, but a live substitution hazard if
# that ever changes). Both fall through the generic sans-serif chain to DejaVu Sans, which has no
# Thai glyphs at all. This alias file closes that specific gap.
#
# <alias>/<prefer> is WEAK-binding: it only supplies Garuda when nothing literally named "Tahoma" or
# "Lucida Grande" is installed. When the real backend/fonts/tahoma.ttf IS present (the normal,
# fonts-supplied build), the exact-name match wins and this alias never fires — confirmed
# empirically in an isolated two-image test (fc-match "Tahoma:style=Regular" -> Garuda|Regular with
# only this alias installed, vs Tahoma|Regular once the real tahoma.ttf is added alongside it), and
# reconfirmed by the per-face check below still passing unchanged on a fonts-supplied build.
RUN mkdir -p /etc/fonts/conf.d && \
    { \
      echo '<?xml version="1.0"?>'; \
      echo '<!DOCTYPE fontconfig SYSTEM "fonts.dtd">'; \
      echo '<fontconfig>'; \
      echo '  <alias><family>Tahoma</family><prefer><family>Garuda</family></prefer></alias>'; \
      echo '  <alias><family>Lucida Grande</family><prefer><family>Garuda</family></prefer></alias>'; \
      echo '</fontconfig>'; \
    } > /etc/fonts/conf.d/99-glr-thai-fallback.conf

# Install bundled fonts used by Excel templates and LibreOffice PDF rendering.
# These are proprietary (Angsana/Browallia/Cordia New, Tahoma, Arial, Calibri, Cambria) and MUST
# NEVER be committed to this repo — backend/fonts/ is git-ignored and must be populated with the
# real .ttf/.ttc files by whatever runs `docker build` (fetched from private storage you control),
# not by git. See backend/fonts/README.md for how to supply them.
COPY fonts/ /usr/local/share/fonts/glr/

# Rebuild the font cache, then verify the required families where they are present.
#
# AN EMPTY backend/fonts/ WARNS AND CONTINUES. #670 made it fatal, which was the right instinct —
# a fontless image renders customer documents in substitutes, and warning about it in a build log
# nobody reads is how that shipped unnoticed for months. But Render builds this Dockerfile from a
# fresh git clone, where the licensed fonts are git-ignored and the folder is ALWAYS empty, so
# fatal means the service cannot deploy at all. Reverted under a UAT deadline (#666 stays open).
#
# UAT #3 (2026-08/09, "PDF อ่านภาษาไทยไม่ออก"): "renders customer documents in substitutes" used to
# mean UNREADABLE, not just metrically off. Before fonts-thai-tlwg was added above, a fontless build
# had NO Thai-capable font at all, so LibreOffice's last fallback (DejaVu) rasterized every Thai
# character as a hollow .notdef box — exactly what UAT saw, because the UAT Render service builds
# this Dockerfile from a fresh clone and so always takes this warn-and-continue branch. Installing
# fonts-thai-tlwg plus the Tahoma/Lucida Grande alias above closes that: a fontless build is now
# METRICALLY WRONG but READABLE — Thai renders in Kinnari/Garuda/Umpush instead of the licensed
# Angsana/Browallia/Cordia New, with different spacing and line-wrap, but never as boxes. The
# licensed fonts remain what you need for correct metrics and visual fidelity; nothing here changes
# that requirement or makes an empty backend/fonts/ acceptable for a real deploy — it only
# guarantees legibility as a floor.
#
# Do not "fix" this back to fatal without first moving the service to a pre-built image
# (scripts/build-push-backend-image.sh, still in place) — otherwise you break every deploy.
#
# --build-arg ALLOW_MISSING_FONTS=false makes it fatal again, for a build that SHOULD carry fonts.
#
# The check is per FAMILY+STYLE, not per family, and that distinction is the whole point.
#
# fc-match never fails. Asked for something it does not have, it cheerfully returns whatever it
# would substitute — and it substitutes the two halves INDEPENDENTLY:
#
#   fc-match "Nonexistent Font:style=Bold"  ->  Verdana|Bold        <- family fell back
#   fc-match "BrowalliaUPC:style=Bold"      ->  BrowalliaUPC|Regular <- STYLE fell back, family held
#
# That second line is why a family-only check is not enough, and it is not hypothetical: this image
# was built for months from a backend/fonts/ that had angsa.ttf but no angsab.ttf. "Angsana New"
# was registered and resolved to itself, so the old family-only loop passed — while every BOLD
# Angsana cell (the company header, the ใบเสนอราคา title, the totals row) rendered in a substitute.
# Comparing the family|style PAIR is what catches it. fonts-thai-tlwg does not change this: Kinnari/
# Garuda/Umpush are never equal to the literal "Angsana New"/"Browallia New"/"Cordia New" strings
# the loop asks for, so a fontless (or partially-fontless) build still fails exactly as before when
# ALLOW_MISSING_FONTS=false — confirmed below, Case C.
#
# The families and styles below are exactly what the three live templates reference — see the table
# in backend/fonts/README.md. If a template is edited to use a new style, add it here too, or that
# one style will substitute silently and nothing will say so. Defined once via `set --` so the
# warn-branch (informational, below) and the strict branch (pass/fail) can never drift apart.
ARG ALLOW_MISSING_FONTS=true
RUN fc-cache -f -v && \
    set -- "Arial|Regular" "Calibri|Regular" "Calibri|Bold" "Calibri|Italic" "Cambria|Bold" \
           "Tahoma|Regular" "Angsana New|Regular" "Angsana New|Bold" \
           "Browallia New|Regular" "Browallia New|Bold" "BrowalliaUPC|Regular" "Cordia New|Regular" && \
    if ! find /usr/local/share/fonts/glr -type f \( -iname '*.ttf' -o -iname '*.ttc' -o -iname '*.otf' \) -print -quit | grep -q .; then \
      if [ "$ALLOW_MISSING_FONTS" = "true" ]; then \
        echo "WARNING: backend/fonts/ is empty — this image renders customer documents in SUBSTITUTE fonts (#666). Expected for a build from a fresh clone; NOT acceptable long-term." >&2; \
        echo "WARNING: Thai stays READABLE (fonts-thai-tlwg + the Tahoma/Lucida Grande alias, UAT #3) but metrics will differ from the licensed originals. Resolved substitute for each required face:" >&2; \
        for spec in "$@"; do \
          family="${spec%|*}"; style="${spec#*|}"; \
          got=$(fc-match --format '%{family[0]}|%{style[0]}' "$family:style=$style" 2>/dev/null); \
          echo "  $spec  ->  $got" >&2; \
        done; \
      else \
        echo "ERROR: backend/fonts/ is empty and ALLOW_MISSING_FONTS=false. Fetch the licensed fonts before docker build — see backend/fonts/README.md." >&2; \
        exit 1; \
      fi; \
    else \
      fail=0; \
      for spec in "$@"; do \
        family="${spec%|*}"; style="${spec#*|}"; \
        got=$(fc-match --format '%{family[0]}|%{style[0]}' "$family:style=$style" 2>/dev/null); \
        if [ "$got" != "$spec" ]; then \
          echo "ERROR: asked fontconfig for '$spec' and got '$got' — it is SUBSTITUTING. Customer PDFs would render wrong. See #666." >&2; \
          fail=1; \
        fi; \
      done; \
      [ "$fail" -eq 0 ] || exit 1; \
      echo "All $# required font faces resolve exactly (family AND style)."; \
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
