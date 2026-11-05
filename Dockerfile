FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

ENV PORT=8000
EXPOSE 8000
# نحدّث yt-dlp عند كل تشغيل لأن يوتيوب يغيّر موقعه باستمرار
CMD pip install --no-cache-dir -U "yt-dlp[default]" >/dev/null 2>&1; \
    uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --proxy-headers --forwarded-allow-ips="*"
