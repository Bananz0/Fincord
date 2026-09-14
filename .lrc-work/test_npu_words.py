import time
import wave
from pathlib import Path

import numpy as np
import openvino_genai as ov_genai


root = Path(r"C:\Users\glenm\.cache\accord-npu-test")
with wave.open(str(root / "npu-sample.wav"), "rb") as wav:
    assert wav.getframerate() == 16000 and wav.getnchannels() == 1
    raw = wav.readframes(wav.getnframes())
audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0

started = time.perf_counter()
pipeline = ov_genai.WhisperPipeline(
    root / "model-small",
    "NPU",
    word_timestamps=True,
    STATIC_PIPELINE=True,
)
compiled = time.perf_counter()
result = pipeline.generate(
    audio,
    return_timestamps=True,
    word_timestamps=True,
    language="<|en|>",
    task="transcribe",
)
finished = time.perf_counter()
words = list(result.words)
print(f"compile_seconds={compiled - started:.3f}")
print(f"inference_seconds={finished - compiled:.3f}")
print(f"word_count={len(words)}")
for word in words[:20]:
    print(f"{word.start_ts:.2f}-{word.end_ts:.2f} {word.word!r}")
if len(words) < 5:
    raise SystemExit("NPU did not return enough word-level timestamps")
