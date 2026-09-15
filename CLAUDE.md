# story-builder

Local microservice: story text in, narrated animation `.mp4` out.

**The spec is [docs/PIPELINE.md](docs/PIPELINE.md)** — the four stages (Story Breakdown → Asset
Generation → Timeline Layout & Motion Math → Video Rendering), the 1920x1080 grid, and the motion
formula `Vertical Position = 500 - (Current Time * 15)` at 24 fps (the `text-scroll` skill). The
illustrated Shorts mode built on it is described in [docs/AI-PIPELINE.md](docs/AI-PIPELINE.md):
**videos ≤ 60 s long, delivered ≤ 60 s after the request**. Keep the code aligned with both.

## Stack
Kotlin 2.3 · Spring Boot 4.1 (Spring MVC, virtual threads) · JDK 21 · Gradle wrapper (Kotlin DSL) ·
Jackson 3 (`tools.jackson`) · ktlint (`ktlint_official`, see `.editorconfig`).
External: `ffmpeg`/`ffprobe` (libx264); a TTS engine (macOS `say`, Piper, or `espeak-ng`;
`storybuilder.tts.engine=auto` picks in that order); Ollama for the scene director; fal.ai for images.

## Commands
- `./gradlew build` — ktlint + all tests (HTTP integration tests need ffmpeg, else they are skipped)
- `./gradlew ktlintFormat` — fix style
- `./gradlew bootRun` — serve on :8080 (Ollama at localhost:11434; `FAL_KEY` env for real images)
- `./gradlew e2eTest -Pe2e.baseUrl=http://localhost:8080 -Pe2e.apiKey=<key> [-Pe2e.requireAi=true]` — black-box suite (`@Tag("e2e")`, `DeployedServiceE2ETest`) against a running instance; excluded from `build`
- `docker compose up -d --build` — Linux image (JRE 21 + ffmpeg + Piper + espeak-ng); settings from `.env` (see `.env.example`); `--profile ollama` adds Ollama for Linux hosts

## Layout (`src/main/kotlin/com/lucy/storybuilder/`)
- `api/` — `StoryController` (one-shot `POST /api/v1/stories`, jobs, video), `ScriptController` (staged `/api/v1/scripts` create/get/put/render, `/api/v1/skills`), DTOs, `JobSpecFactory` (request → skill → config precedence), ProblemDetail errors
- `security/ApiKeyFilter` — `X-API-Key` on `/api/**` when `storybuilder.security.api-keys` is set
- `skills/` — `SkillRegistry` loads `resources/skills/<name>/{skill.yaml,director.md,image.md}` (+ `storybuilder.skills.dir`); `PromptTemplate` `{{placeholder}}` fill, validated at startup
- `script/` — `ScriptDirector` (LLM → validated `StoryScript`, deadline-aware, rule-based fallback), `ScriptStore`
- `ai/` — `LlmClient` (`OllamaLlmClient` with JSON-schema `format`, `OpenAiCompatibleLlmClient`), `ImageProvider` (`FalImageProvider`, `PlaceholderImageProvider`), wired in `AiConfig`
- `job/` — `JobStore`, `JobRunner` (fixed pool), `StoryPipeline` (the 4 stages, timings, SLA, fallbacks), `PipelineStats` (measured render/asset averages that size the director's time budget)
- `pipeline/breakdown` — rule-based paragraph → scene split (text-scroll and director fallback)
- `pipeline/assets` — `TtsEngine` (`synthesizeAll`; Piper batches via `resources/tts/piper_batch.py`), `SceneImageService` (parallel, retry, placeholder fallback), `AudioAssembler` (pad/trim/`atempo`/concat), `TextLayout`
- `pipeline/timeline` — `Timeline`, `LinearScroll`, `CameraMotion`, `Captions`, `DurationBudget` (60 s cap)
- `pipeline/render` — `SceneRenderer` (`TextScrollRenderer`, `IllustratedRenderer`), `ParallelFrameSource` (multi-threaded drawing, ordered output), `FfmpegEncoder`
- `process/` — `ProcessRunner` (no shell; drains stderr to avoid pipe deadlocks), `Ffmpeg` helpers

## Conventions
- Never build shell strings; pass argument lists to `ProcessRunner`. Scene text reaches every TTS engine via a file, never argv.
- Secrets (FAL_KEY, API keys) come from env / `.env` only — never logged, never in errors, never committed.
- Anything slow or external must have a timeout and a fallback that still delivers a video; record it in `job.notes` and set `degraded`.
- Keep the Docker image and the Linux README steps in sync when adding a binary dependency.
- Format seconds for ffmpeg with `ffSeconds()` (locale-safe).
- Scene durations are whole frames so narration and video cut together; audio stays PCM WAV until the final AAC mux.
- Config lives under `storybuilder.*` in `application.yml` (`StoryBuilderProperties`). Per-job files go to `work/{jobId}/` (git-ignored); only `output.mp4` and `script.json` are kept.
- Beware: `/api/**` inside a KDoc opens a nested comment in Kotlin.
