# Illustrated Shorts pipeline

This extends [PIPELINE.md](PIPELINE.md). The same four stages now produce a vertical, cartoon
"Shorts" video instead of scrolling text. **Targets:** the video is at most 60 s long, and it is
ready within 60 s of `POST /api/v1/stories`.

```
story ──► 1. DIRECTOR ──► scene script ──► 2. ASSETS ─────────────────► 3. TIMELINE ──► 4. RENDER ──► .mp4
          Ollama LLM      title,            ├─ narration: Piper, one    fit ≤ 60 s:     4 threads draw
          + skill's       characters        │  process for all scenes   speed up narration  pictures with
          director.md     (fixed looks),    └─ pictures: fal.ai FLUX,   ≤ 1.25x, then       camera moves,
                          3-6 scenes           all scenes in parallel,  drop trailing       crossfades and
                                               from skill's image.md    scenes; frame grid  captions → x264
```

## Stages
1. **Director** (`script/ScriptDirector`)
   - Fills the skill's `director.md` and asks the LLM for JSON constrained by a JSON schema.
     With Ollama this is grammar-constrained, so small models can't produce broken JSON.
   - Output: a title, characters with a fixed visual **look**, and 3–6 scenes. Each scene has
     narration, setting, action, emotion, a camera move, and the characters visible in it.
2. **Assets**
   - **Narration:** one `TtsEngine.synthesizeAll` call. Piper runs `piper_batch.py`, which loads the
     voice once for the whole job.
   - **Pictures:** `SceneImageService`, with all scenes requested at once.
   - **Consistency:** every image prompt repeats each visible character's look, and every picture
     in a script uses the same seed.
3. **Timeline:** `DurationBudget` fits the 60 s limit, then `TimelineBuilder` quantises scenes to
   whole frames so audio and video cut together.
4. **Render**
   - `IllustratedRenderer` moves each picture with `CameraMotion`: eased zoom in/out between 1.0
     and 1.15, or a pan left, right or up at 1.12 zoom.
   - Scenes crossfade, and `Captions` shows 3–6 word chunks timed across the narration.
   - `ParallelFrameSource` draws frames on up to 4 threads and feeds FFmpeg in order.

## The 60-second delivery budget
The director is the slow, variable part, so it gets whatever time is left:

```
director budget = 60 s − time already spent (e.g. queued)
                        − expected render (estimated video length × fps × measured ms per frame)
                        − expected assets (measured running average)
                        − 3 s safety margin
                  clamped to 4 s … storybuilder.llm.timeout
```
The render and asset estimates come from `PipelineStats`, a running average of this machine's
recent jobs. If the director overruns its budget, the LLM call is cancelled and a **rule-based
script** is used instead. The video still ships on time, marked `degraded`.

| What goes wrong | What happens instead | Reported |
|---|---|---|
| LLM slow, down, or returns bad JSON | Rule-based scenes: paragraphs, trimmed to the word budget | `degraded`, note |
| Image request fails after 1 retry | Placeholder art for that scene only | `degraded`, note per scene |
| Narration longer than 60 s | Sped up (pitch kept) by at most 1.25x, then trailing scenes dropped | `degraded`, note |
| Job finishes after 60 s | Delivered anyway, with a warning log | `slaMet: false` |

## Measured performance
Measured on an Apple M1 with 8 GB RAM (heavily swapping), using `llama3.2:3b` in native Ollama, a
~110-word story, 5 scenes, and placeholder art (no `FAL_KEY`). Narration was macOS `say` natively
and Piper in Docker:

| Where | Director | Assets | Render | **Total** | Video |
|---|---|---|---|---|---|
| Native (`bootRun`, macOS `say`) | 26 s | 8 s | 5 s | **40 s** | 16–19 s |
| Docker (4 vCPU, 2 GB VM) | 24 s | 4 s | 11 s | **40 s** | 18 s |

What made the difference:
- **Parallel frame drawing:** 8.5 → 3.4 ms per frame.
- **Batch Piper:** assets went from 32 s to 4 s in Docker.
- **A leaner director prompt:** 708 → about 470 output tokens.

On this machine the director runs at about 20 tokens/s and is most of the budget. With an NVIDIA
GPU running Ollama (the Linux `--profile ollama` setup), expect it to take a few seconds.

## Tuning
- **Faster director:** `STORYBUILDER_LLM_MODEL` accepts any Ollama model. Smaller ones are faster
  but write worse character looks; `llama3.2:1b` was not good enough in testing.
- **Any OpenAI-compatible host:** set `storybuilder.llm.provider=openai-compatible` and
  `storybuilder.llm.base-url=.../v1` (vLLM, LM Studio, a hosted API).
- **Shorter or longer videos:** change a skill's `maxWords` and `scenes` limits.
- **Quality vs speed:** set a skill's `format.crf` and `format.preset`; the default, `23 / veryfast`,
  is "medium".
