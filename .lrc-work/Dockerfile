FROM python:3.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TORCH_HOME=/models

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

# Keep the CPU-only PyTorch wheel; the default package can pull CUDA libraries that phastos cannot
# use and would waste several gigabytes of its constrained OS disk.
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu \
        torch==2.2.2 torchaudio==2.2.2 \
    && pip install --no-cache-dir demucs==4.0.1 \
    && pip install --no-cache-dir "numpy==1.26.4" "faster-whisper==1.2.1" "mutagen==1.47.0"

WORKDIR /app
COPY service.py /app/service.py
# The shared alignment rules. Pure Python, no dependencies, so it adds nothing to the
# image beyond the file itself.
COPY elrc_rules.py /app/elrc_rules.py
COPY enhanced_lrc.py /app/enhanced_lrc.py
COPY enhanced_lrc_receiver.py /app/enhanced_lrc_receiver.py
COPY enhanced_lrc_gpu_api.py /app/enhanced_lrc_gpu_api.py

EXPOSE 8097
CMD ["python", "/app/service.py"]
