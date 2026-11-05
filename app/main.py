"""موقع تحميل الفيديوهات - سيرفر FastAPI مبني على yt-dlp و ffmpeg."""

from __future__ import annotations

import asyncio
import os
import re
import shutil
import tempfile
import time
from pathlib import Path
from urllib.parse import quote, urlparse

import yt_dlp
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from starlette.background import BackgroundTask

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"

# إعدادات قابلة للتعديل من متغيرات البيئة
MAX_CONCURRENT_DOWNLOADS = int(os.getenv("MAX_CONCURRENT_DOWNLOADS", "3"))
MAX_DURATION_SECONDS = int(os.getenv("MAX_DURATION_SECONDS", str(3 * 60 * 60)))  # 3 ساعات
MAX_FILESIZE_BYTES = int(os.getenv("MAX_FILESIZE_MB", "4096")) * 1024 * 1024
RATE_LIMIT_PER_MINUTE = int(os.getenv("RATE_LIMIT_PER_MINUTE", "20"))
COOKIES_FILE = os.getenv("COOKIES_FILE")  # اختياري: ملف كوكيز لتجاوز حظر يوتيوب
PROXY = os.getenv("YTDLP_PROXY")  # اختياري: بروكسي


def _find_ffmpeg() -> str | None:
    """يستخدم ffmpeg المثبت في الجهاز، وإذا ما لقاه يستخدم النسخة اللي تجي مع imageio-ffmpeg."""
    if shutil.which("ffmpeg"):
        return None  # yt-dlp بيلقاه لحاله
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:  # noqa: BLE001
        return None


FFMPEG_LOCATION = _find_ffmpeg()

QUALITY_LABELS = {
    4320: "8K",
    2160: "4K",
    1440: "2K",
    1080: "Full HD",
    720: "HD",
}

app = FastAPI(title="محمّل الفيديوهات")
download_slots = asyncio.Semaphore(MAX_CONCURRENT_DOWNLOADS)
_rate_buckets: dict[str, list[float]] = {}


class InfoRequest(BaseModel):
    url: str


def _client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _check_rate_limit(request: Request) -> None:
    ip = _client_ip(request)
    now = time.monotonic()
    bucket = [t for t in _rate_buckets.get(ip, []) if now - t < 60]
    if len(bucket) >= RATE_LIMIT_PER_MINUTE:
        raise HTTPException(429, "طلبات كثيرة، حاول بعد دقيقة.")
    bucket.append(now)
    _rate_buckets[ip] = bucket


def _validate_url(url: str) -> str:
    url = url.strip()
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise HTTPException(400, "الرابط غير صحيح.")
    return url


def _base_opts() -> dict:
    opts: dict = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        # نمنع المستخرج العام حتى ما يقدر أحد يخلي السيرفر يفتح روابط داخلية (SSRF)
        "allowed_extractors": ["default", "-generic"],
        "socket_timeout": 30,
    }
    if COOKIES_FILE:
        opts["cookiefile"] = COOKIES_FILE
    if PROXY:
        opts["proxy"] = PROXY
    if FFMPEG_LOCATION:
        opts["ffmpeg_location"] = FFMPEG_LOCATION
    return opts


def _friendly_error(exc: Exception) -> HTTPException:
    msg = str(exc)
    if "Unsupported URL" in msg or "No suitable extractor" in msg:
        return HTTPException(400, "هذا الموقع غير مدعوم.")
    if "Sign in to confirm" in msg or "bot" in msg.lower():
        return HTTPException(503, "يوتيوب حظر السيرفر مؤقتاً. جرّب لاحقاً أو أضف ملف كوكيز.")
    if "Private video" in msg or "private" in msg.lower():
        return HTTPException(403, "الفيديو خاص أو محذوف.")
    return HTTPException(400, "ما قدرنا نجيب الفيديو. تأكد من الرابط.")


def _extract_info(url: str) -> dict:
    with yt_dlp.YoutubeDL(_base_opts()) as ydl:
        info = ydl.extract_info(url, download=False)
    if info.get("_type") == "playlist":
        entries = [e for e in info.get("entries") or [] if e]
        if not entries:
            raise HTTPException(400, "ما فيه فيديو في هذا الرابط.")
        info = entries[0]
    return info


def _quality_label(height: int) -> str:
    for h, label in QUALITY_LABELS.items():
        if height >= h:
            return f"{height}p ({label})" if label else f"{height}p"
    return f"{height}p"


def _summarize(info: dict) -> dict:
    formats = info.get("formats") or []
    heights: dict[int, int | None] = {}
    for f in formats:
        h = f.get("height")
        if not h or f.get("vcodec") in (None, "none"):
            continue
        size = f.get("filesize") or f.get("filesize_approx")
        if h not in heights or (size and (heights[h] or 0) < size):
            heights[h] = size
    has_audio = any(f.get("acodec") not in (None, "none") for f in formats)

    options = [
        {"id": str(h), "label": _quality_label(h), "type": "video", "approx_size": heights[h]}
        for h in sorted(heights, reverse=True)
    ]
    if not options and info.get("url"):
        options.append({"id": "best", "label": "أفضل جودة", "type": "video", "approx_size": None})
    if has_audio or options:
        options.append({"id": "audio", "label": "صوت فقط (MP3)", "type": "audio", "approx_size": None})

    return {
        "title": info.get("title") or "فيديو",
        "thumbnail": info.get("thumbnail"),
        "duration": info.get("duration"),
        "uploader": info.get("uploader") or info.get("channel"),
        "site": info.get("extractor_key"),
        "webpage_url": info.get("webpage_url"),
        "options": options,
    }


@app.post("/api/info")
async def video_info(body: InfoRequest, request: Request) -> JSONResponse:
    _check_rate_limit(request)
    url = _validate_url(body.url)
    try:
        info = await asyncio.to_thread(_extract_info, url)
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        raise _friendly_error(exc) from exc
    return JSONResponse(_summarize(info))


def _safe_filename(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\r\n\t]+', " ", name).strip()
    return (name[:150] or "video").strip()


def _download(url: str, quality: str, workdir: str) -> Path:
    opts = _base_opts()
    opts.update(
        {
            "outtmpl": os.path.join(workdir, "%(title).150B.%(ext)s"),
            "max_filesize": MAX_FILESIZE_BYTES,
            "match_filter": yt_dlp.utils.match_filter_func(f"!is_live & duration <=? {MAX_DURATION_SECONDS}"),
            "concurrent_fragment_downloads": 4,
        }
    )
    if quality == "audio":
        opts["format"] = "bestaudio/best"
        opts["postprocessors"] = [
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"}
        ]
    else:
        if quality == "best":
            fmt = "bv*+ba/b"
        elif quality.isdigit():
            h = int(quality)
            fmt = f"bv*[height<={h}]+ba/b[height<={h}]/bv*+ba/b"
        else:
            raise HTTPException(400, "الجودة غير صحيحة.")
        opts["format"] = fmt
        # mp4 يشتغل على كل الأجهزة. الجودات العالية (4K/8K) غالباً VP9/AV1 وتنحط داخل mp4 عادي
        opts["merge_output_format"] = "mp4"

    with yt_dlp.YoutubeDL(opts) as ydl:
        ydl.extract_info(url, download=True)

    files = [p for p in Path(workdir).iterdir() if p.is_file() and not p.name.endswith((".part", ".ytdl"))]
    if not files:
        raise HTTPException(400, "الفيديو أطول أو أكبر من المسموح، أو بث مباشر.")
    return max(files, key=lambda p: p.stat().st_size)


@app.get("/api/download")
async def download(request: Request, url: str = Query(...), quality: str = Query("best")):
    _check_rate_limit(request)
    url = _validate_url(url)

    if download_slots.locked():
        raise HTTPException(503, "السيرفر مشغول، حاول بعد شوي.")

    async with download_slots:
        workdir = tempfile.mkdtemp(prefix="dl_")
        try:
            path = await asyncio.to_thread(_download, url, quality, workdir)
        except HTTPException:
            shutil.rmtree(workdir, ignore_errors=True)
            raise
        except Exception as exc:  # noqa: BLE001
            shutil.rmtree(workdir, ignore_errors=True)
            raise _friendly_error(exc) from exc

    filename = _safe_filename(path.stem) + path.suffix
    headers = {"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"}
    return FileResponse(
        path,
        headers=headers,
        media_type="application/octet-stream",
        background=BackgroundTask(shutil.rmtree, workdir, ignore_errors=True),
    )


@app.get("/api/health")
async def health() -> dict:
    return {"ok": True, "yt_dlp": yt_dlp.version.__version__, "ffmpeg": bool(shutil.which("ffmpeg") or FFMPEG_LOCATION)}


app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
