# story-builder

Local microservice: story text in, narrated animation `.mp4` out.

**The spec is [docs/PIPELINE.md](docs/PIPELINE.md)** — the four stages (Story Breakdown → Asset
Generation → Timeline Layout & Motion Math → Video Rendering), the 1920x1080 grid, and the motion
formula `Vertical Position = 500 - (Current Time * 15)` at 24 fps. Keep the code aligned with it.

## Stack
Kotlin 2.3 · Spring Boot 4.1 (Spring MVC, virtual threads) · JDK 21 · Gradle wrapper (Kotlin DSL) ·
Jackson 3 (`tools.jackson`) · ktlint (`ktlint_official`, see `.editorconfig`).
External binaries: `ffmpeg`/`ffprobe` (with libx264) plus a TTS engine — macOS `/usr/bin/say`,
Piper (`pip install piper-tts`, neural) or `espeak-ng`. `storybuilder.tts.engine=auto` picks say → piper → espeak.

## Commands
- `./gradlew build` — ktlint + all tests (the HTTP end-to-end test needs ffmpeg, else it is skipped)
- `./gradlew ktlintFormat` — fix style
- `./gradlew bootRun` — serve on :8080
- `./gradlew e2eTest -Pe2e.baseUrl=http://localhost:8080` — black-box suite (`@Tag("e2e")`, `DeployedServiceE2ETest`) against a running instance; excluded from `build`
- `docker compose up -d --build` — Linux image (Ubuntu + JRE 21 + ffmpeg + Piper + espeak-ng), Piper by default

## Layout (`src/main/kotlin/com/lucy/storybuilder/`)
- `api/` — `StoryController` (`POST /api/v1/stories`, `GET /api/v1/jobs/{id}`, `GET /api/v1/jobs/{id}/video`), DTOs, `JobSpecFactory` (request options over config defaults), ProblemDetail errors
- `job/` — in-memory `JobStore`, `JobRunner` (fixed pool, `max-concurrent-jobs`), `StoryPipeline` (runs the 4 stages)
- `pipeline/breakdown` — stage 1: paragraphs → scenes, reading-speed durations
- `pipeline/assets` — stage 2: `TtsEngine` (`say` | `piper` | `espeak` | `silent`, chosen in `TtsConfig`), `TextLayout` word-wrap, `AudioAssembler`
- `pipeline/timeline` — stage 3: frame-quantized `Timeline`, `MotionFunction` / `LinearScroll`
- `pipeline/render` — stage 4: `FrameRenderer` (Java2D → bgr24 bytes), `FfmpegEncoder` (frames on stdin + narration → H.264/AAC mp4)
- `process/` — `ProcessRunner` (no shell; drains stderr to avoid pipe deadlocks), `Ffmpeg` helpers

## Conventions
- Never build shell strings; pass argument lists to `ProcessRunner`. Scene text reaches every TTS engine via a file, never argv.
- Keep the Docker image and the Linux README steps in sync when adding a binary dependency.
- Format seconds for ffmpeg with `ffSeconds()` (locale-safe).
- Scene durations are whole frames so narration and video cut together; audio stays PCM WAV until the final AAC mux.
- Config lives under `storybuilder.*` in `application.yml` (`StoryBuilderProperties`). Per-job files go to `work/{jobId}/` (git-ignored); only `output.mp4` is kept.
