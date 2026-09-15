"""Synthesize every scene of a job with ONE Piper model load.

The piper CLI reloads Python and the voice model on every call (~1-3 s each on small hosts);
loading once and looping keeps narration for a whole Shorts video to a few seconds.

Usage: python piper_batch.py jobs.json
jobs.json: {"model": "/path/voice.onnx", "length_scale": 1.0,
            "jobs": [{"text": "...", "output": "/path/scene-000.wav"}, ...]}
"""

import json
import sys
import wave

from piper import PiperVoice, SynthesisConfig


def main() -> None:
    with open(sys.argv[1], encoding="utf-8") as f:
        spec = json.load(f)
    voice = PiperVoice.load(spec["model"])
    config = SynthesisConfig(length_scale=spec.get("length_scale"))
    for job in spec["jobs"]:
        with wave.open(job["output"], "wb") as out:
            voice.synthesize_wav(job["text"], out, syn_config=config)


if __name__ == "__main__":
    main()
