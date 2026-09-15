# story-builder
Based on provided story , generates animation video files

A local Spring Boot service that turns a written story into a narrated animated video. By default it
makes a **vertical Shorts-style cartoon** (720x1280, 30 fps, at most 60 s long, delivered within
60 s):
1. A local LLM (Ollama) rewrites the story as a scene script.
2. An image model (fal.ai FLUX-schnell) paints one cartoon illustration per scene.
3. The pictures move with camera pans and zooms, with captions and Piper narration.

The original scrolling-text look is still available as the `text-scroll` skill.

- Original four-stage design: [docs/PIPELINE.md](docs/PIPELINE.md)
- Illustrated mode, time budget and fallbacks: [docs/AI-PIPELINE.md](docs/AI-PIPELINE.md)

## Style skills
A **skill** is a named preset of pre-filled prompts and look settings in
[`src/main/resources/skills/<name>/`](src/main/resources/skills):
- `skill.yaml`: format, style, voice, captions, camera moves.
- `director.md`: the scene-director prompt.
- `image.md`: the image-prompt template.

Pick one per request with `"skill": "<name>"`. Add your own by dropping a folder into
`storybuilder.skills.dir`.

| Skill | Look |
|---|---|
| `cartoon-storybook` *(default)* | Bright 2D children's-picture-book cartoon |
| `anime` | Cel-shaded anime with cinematic light |
| `watercolor-bedtime` | Soft pastel watercolor, slower and calmer narration |
| `comic-book` | Bold ink, halftone, punchy comic panels |
| `text-scroll` | The original look: scrolling text on a plain background, 1920x1080, no AI |

## Example: story in, animation out (`text-scroll`)

**The story** ([docs/media/demo-request.json](docs/media/demo-request.json)):

> Once upon a time, a small lighthouse stood alone at the edge of the sea. Every night it sent a
> single beam across the dark water.
>
> One stormy evening, a fishing boat lost its way among the rocks. The keeper turned the lamp as
> bright as it would go.
>
> The boat followed the light home, and the lighthouse was never lonely again.

**The request:**
```bash
curl -X POST localhost:8080/api/v1/stories \
  -H 'Content-Type: application/json' -H "X-API-Key: $STORYBUILDER_API_KEY" \
  -d @docs/media/demo-request.json
```

**The generated animation.** It's 25 s long and each paragraph becomes a scene that fades in, drifts
upward at 15 px/s, and fades out:

![Generated animation of the lighthouse story](docs/media/lighthouse-story.gif)

▶️ **[Watch the full video with narration (MP4, 1920x1080, 24 fps, 860 KB)](docs/media/lighthouse-story.mp4).**
The GIF above is a silent 640 px preview. The MP4 is the exact service output, narrated by the
Piper `en_US-lessac-medium` voice.

How the story was split into scenes (`GET /api/v1/jobs/{id}`):

| Scene | Text | Words | On screen |
|---|---|---|---|
| 0 | Once upon a time, a small lighthouse stood alone… | 26 | 10.42 s |
| 1 | One stormy evening, a fishing boat lost its way… | 23 | 9.21 s |
| 2 | The boat followed the light home… | 13 | 5.54 s |

## Run with Docker (Linux amd64 / arm64)
The image bundles Java 21, FFmpeg, Piper (natural neural voice, default) and espeak-ng (light fallback).
The AI pieces stay outside the image: Ollama on the host or in its own container, and fal.ai over HTTPS.

```bash
cp .env.example .env    # then set STORYBUILDER_API_KEY and FAL_KEY
```
- **macOS:** Ollama runs natively on the GPU. The container reaches it at `host.docker.internal`.
  ```bash
  brew install ollama && ollama serve &
  ollama pull llama3.2:3b
  docker compose up -d --build
  ```
- **Linux server:** Ollama runs as a container. Set `STORYBUILDER_LLM_BASE_URL=http://ollama:11434` in `.env`, then:
  ```bash
  docker compose --profile ollama up -d --build
  ```
  Uncomment the GPU section in `docker-compose.yml` if the host has an NVIDIA GPU.

More options:
- **Fallback voice:** `STORYBUILDER_TTS_ENGINE=espeak`.
- **More Piper voices:** `docker build --build-arg PIPER_VOICES="en_US-lessac-medium en_GB-alan-medium" .`
- **Both architectures:** `docker buildx build --platform linux/amd64,linux/arm64 -t <registry>/story-builder:0.1 --push .`
- **Any `storybuilder.*` setting** can be an env var, e.g. `STORYBUILDER_MAX_CONCURRENT_JOBS=4`.
- **Memory:** jobs run one at a time per 1.5 GB of memory and per 4 cores
  (`STORYBUILDER_MAX_CONCURRENT_JOBS=0`, the default). A 2 GB Docker VM runs one job at a time and
  queues the rest; give Docker 4 GB or more to run jobs side by side.
- **Ollama on another machine:** set `STORYBUILDER_LLM_BASE_URL=http://<ip>:11434`, and start Ollama
  there with `OLLAMA_HOST=0.0.0.0:11434` so it accepts network connections.

Without `FAL_KEY` the service still works: scenes get offline placeholder art instead of
illustrations. Without Ollama, a rule-based script is used. Either way the job reports
`degraded: true` and explains why in `notes`.

## Run locally
Prerequisites: JDK 21 and FFmpeg with libx264. The voice engine is picked automatically
(`storybuilder.tts.engine=auto`): macOS `say` → Piper → espeak-ng.

**macOS**
```bash
brew install ffmpeg ollama
ollama serve & ollama pull llama3.2:3b
FAL_KEY=<your fal key> ./gradlew bootRun
```

**Linux (no Docker)**, e.g. Ubuntu/Debian:
```bash
sudo apt-get install -y openjdk-21-jre-headless ffmpeg espeak-ng fonts-dejavu-core python3-venv
# Optional, for the natural voice:
python3 -m venv ~/piper && ~/piper/bin/pip install piper-tts==1.8.0
~/piper/bin/python -m piper.download_voices --data-dir ~/piper/voices en_US-lessac-medium

./gradlew bootJar
STORYBUILDER_TTS_PIPER_BINARY=~/piper/bin/piper STORYBUILDER_TTS_PIPER_DATA_DIR=~/piper/voices \
  java -jar build/libs/app.jar
```
Without Piper installed, `auto` falls back to espeak-ng.

## Use
Every `/api/**` request needs `X-API-Key` when `storybuilder.security.api-keys` is set (Docker
Compose always sets it). `/actuator/health` stays open.

**One-shot:** story in, video out.
```bash
curl -i -X POST localhost:8080/api/v1/stories \
  -H 'Content-Type: application/json' -H "X-API-Key: $STORYBUILDER_API_KEY" \
  -d '{"text":"Pip the fox rescued a baby owl from the river...", "skill":"cartoon-storybook"}'
```
The response is `202 Accepted` with a `jobId`. Poll the status, then download the video:
```bash
curl -H "X-API-Key: $STORYBUILDER_API_KEY" localhost:8080/api/v1/jobs/<jobId>
curl -H "X-API-Key: $STORYBUILDER_API_KEY" -o story.mp4 localhost:8080/api/v1/jobs/<jobId>/video
```

**Staged:** review and edit the scene script before anything is drawn or spoken.
```bash
curl -X POST .../api/v1/scripts -d '{"text":"...", "skill":"anime"}'   # 201: the script
curl -X PUT  .../api/v1/scripts/<id> -d '{"scenes":[{"narration":"...", "setting":"...", "camera":"pan_left"}]}'
curl -X POST .../api/v1/scripts/<id>/render                            # 202: a job, as above
```
In a `PUT`, `scenes` and `characters` replace the whole list. Image prompts are rebuilt from the
skill's template on every edit, so a changed character look or setting always reaches the pictures.
The only exception is a scene whose `imagePrompt` you wrote yourself (`imagePromptCustom: true`).
To go back to a generated prompt, send it empty.

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/v1/stories` | `202` + `{ jobId, status, skill, statusUrl, videoUrl }` |
| `GET` | `/api/v1/jobs/{id}` | See below |
| `GET` | `/api/v1/jobs/{id}/video` | `video/mp4` when done (supports seeking), `409` while rendering, `404` if unknown |
| `POST` | `/api/v1/scripts` | `201` + scene script (title, characters with fixed looks, scenes) |
| `GET` / `PUT` | `/api/v1/scripts/{id}` | Read or edit the script |
| `POST` | `/api/v1/scripts/{id}/render` | `202` + job |
| `GET` | `/api/v1/skills` | Available style skills |

`GET /api/v1/jobs/{id}` returns:
- `status`: `QUEUED → BREAKDOWN → ASSETS → LAYOUT → RENDERING → DONE / FAILED`.
- `progress`, `scenes`, `durationSeconds`, `scriptId`.
- `timingsMs`: time per stage (`director`, `assets`, `render`, `total`).
- `slaMet`: whether it was delivered within 60 s.
- `degraded` and `notes`: whether and why a fallback was used.

Optional `options` in a story or render body override the skill:
```json
{ "width": 720, "height": 1280, "fps": 30, "wordsPerMinute": 150, "voice": "en_US-lessac-medium" }
```
- **`text-scroll` only:** `startY`, `scrollSpeed`, `backgroundColor`, `textColor`, `fontSize`.
- **`voice`** depends on the engine: a `say -v '?'` name on macOS, a Piper model name, or an espeak-ng voice (`en-gb`).
- **Errors** come back as `application/problem+json`.

## Test
```bash
./gradlew build
```
This runs ktlint, the unit tests, and two in-process HTTP tests that render real videos (skipped
without ffmpeg). One of them covers the illustrated one-shot and staged flows, with a fake LLM and
offline art.

**Against a running deployment** (Docker, a Linux server, or `bootRun`). This needs ffmpeg on the
machine running the tests, because it inspects the returned videos:
```bash
./gradlew e2eTest -Pe2e.baseUrl=http://localhost:8080 -Pe2e.apiKey=$STORYBUILDER_API_KEY -Pe2e.requireAi=true
```
It checks:
- A cartoon short is delivered in ≤ 60 s, is ≤ 60 s long, and is 720x1280 at 30 fps, with audible
  narration, a visible picture, and captions.
- The staged script → edit → render flow.
- Authentication, and the skill list.
- The `text-scroll` output: 1080p at 24 fps, an exact 15 px/s scroll, custom options, seeking, and queueing.
- The 400, 404 and 409 errors.

`-Pe2e.requireAi=true` also fails the run if any fallback was used.
