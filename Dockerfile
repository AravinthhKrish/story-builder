# syntax=docker/dockerfile:1
#
# story-builder: Spring Boot API + FFmpeg + Piper (neural TTS) + espeak-ng, on Linux amd64/arm64.
#
#   docker build -t story-builder .
#   docker run -p 8080:8080 -v story-videos:/data/work story-builder
#
# Multi-arch:  docker buildx build --platform linux/amd64,linux/arm64 -t <registry>/story-builder --push .

ARG JDK_IMAGE=eclipse-temurin:21-jdk
ARG JRE_IMAGE=eclipse-temurin:21-jre

# ---- 1. Build the jar -------------------------------------------------------------------------
# The jar is platform-independent, so compile natively even when cross-building for another arch.
FROM --platform=$BUILDPLATFORM ${JDK_IMAGE} AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain bootJar

# ---- 2. Piper neural TTS: self-contained venv + voice models ----------------------------------
FROM ${JRE_IMAGE} AS piper
ARG PIPER_VERSION=1.8.0
# Space-separated; browse voices at https://huggingface.co/rhasspy/piper-voices
ARG PIPER_VOICES="en_US-lessac-medium"
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3-venv \
 && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/piper/venv \
 && /opt/piper/venv/bin/pip install --no-cache-dir "piper-tts==${PIPER_VERSION}" \
 && mkdir -p /opt/piper/voices \
 && /opt/piper/venv/bin/python -m piper.download_voices --data-dir /opt/piper/voices ${PIPER_VOICES}

# ---- 3. Runtime ---------------------------------------------------------------------------------
FROM ${JRE_IMAGE}
# ffmpeg (libx264 + aac) renders video; espeak-ng is the lightweight TTS fallback; python3 runs the
# Piper venv; DejaVu fonts give Java2D real glyphs for the "SansSerif" font on a headless server.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg espeak-ng python3 fontconfig fonts-dejavu-core curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home storybuilder \
 && mkdir -p /data/work \
 && chown storybuilder /data/work

COPY --from=piper /opt/piper /opt/piper
COPY --from=build /src/build/libs/app.jar /app/app.jar

# Every storybuilder.* setting can be overridden the same way, e.g. STORYBUILDER_MAX_CONCURRENT_JOBS=4.
# Switch to the robotic-but-light fallback voice with STORYBUILDER_TTS_ENGINE=espeak.
ENV PATH="/opt/piper/venv/bin:${PATH}" \
    STORYBUILDER_WORK_DIR=/data/work \
    STORYBUILDER_TTS_ENGINE=piper \
    STORYBUILDER_TTS_PIPER_DATA_DIR=/opt/piper/voices \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"

USER storybuilder
WORKDIR /app
VOLUME /data/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
