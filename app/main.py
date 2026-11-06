"""محمّل - سيرفر FastAPI مبني على yt-dlp و ffmpeg."""

from __future__ import annotations

import asyncio
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import quote, urlparse

import yt_dlp
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .history import History
from .jobs import ACTIVE_STATES, Job, JobManager, JobSpec

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
DATA_DIR = Path(os.getenv("DATA_DIR", BASE_DIR.parent / "data"))

# إعدادات قابلة للتعديل من متغيرات البيئة
MAX_CONCURRENT_DOWNLOADS = int(os.getenv("MAX_CONCURRENT_DOWNLOADS", "3"))
MAX_DURATION_SECONDS = int(os.getenv("MAX_DURATION_SECONDS", str(3 * 60 * 60)))  # 3 ساعات
MAX_FILESIZE_BYTES = int(os.getenv("MAX_FILESIZE_MB", "4096")) * 1024 * 1024
RATE_LIMIT_PER_MINUTE = int(os.getenv("RATE_LIMIT_PER_MINUTE", "30"))
MAX_PLAYLIST_ITEMS = int(os.getenv("MAX_PLAYLIST_ITEMS", "300"))
MAX_JOBS_PER_REQUEST = int(os.getenv("MAX_JOBS_PER_REQUEST", "300"))
COOKIES_FILE = os.getenv("COOKIES_FILE")  # اختياري: ملف كوكيز لتجاوز حظر يوتيوب
PROXY = os.getenv("YTDLP_PROXY")  # اختياري: بروكسي
# للاختبار فقط: يسمح بالروابط المباشرة لملفات الفيديو. لا تفعّله على سيرفر عام (يفتح باب SSRF).
ALLOW_GENERIC = os.getenv("YTDLP_ALLOW_GENERIC") == "1"


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

QUALITY_LABELS = {4320: "8K", 2160: "4K", 1440: "2K", 1080: "Full HD", 720: "HD"}
LANG_NAMES = {
    "ar": "العربية", "en": "الإنجليزية", "fr": "الفرنسية", "es": "الإسبانية", "de": "الألمانية",
    "tr": "التركية", "ur": "الأردية", "hi": "الهندية", "id": "الإندونيسية", "ms": "الملايوية",
    "fa": "الفارسية", "ru": "الروسية", "ja": "اليابانية", "ko": "الكورية", "zh": "الصينية",
    "it": "الإيطالية", "pt": "البرتغالية",
}

app = FastAPI(title="محمّل")
_rate_buckets: dict[str, list[float]] = {}


def _base_opts() -> dict:
    opts: dict = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "socket_timeout": 30,
    }
    if not ALLOW_GENERIC:
        # نمنع المستخرج العام حتى ما يقدر أحد يخلي السيرفر يفتح روابط داخلية (SSRF)
        opts["allowed_extractors"] = ["default", "-generic"]
    if COOKIES_FILE:
        opts["cookiefile"] = COOKIES_FILE
    if PROXY:
        opts["proxy"] = PROXY
    if FFMPEG_LOCATION:
        opts["ffmpeg_location"] = FFMPEG_LOCATION
    return opts


def _friendly_error(exc: Exception) -> str:
    msg = str(exc)
    low = msg.lower()
    if "Unsupported URL" in msg or "No suitable extractor" in msg:
        return "هذا الموقع غير مدعوم."
    if "Sign in to confirm" in msg or "bot" in low:
        return "يوتيوب حظر السيرفر مؤقتاً. جرّب لاحقاً أو أضف ملف كوكيز."
    if "private" in low:
        return "الفيديو خاص أو محذوف."
    if "requested format is not available" in low:
        return "الجودة هذي مو متوفرة لهذا الفيديو."
    if "no space left" in low:
        return "المساحة في الجهاز ما تكفي."
    return "ما قدرنا نجيب الفيديو. تأكد من الرابط."


history = History(DATA_DIR / "history.json")


def _record_history(job: Job) -> None:
    s = job.spec
    history.add(
        {
            "url": s.url,
            "title": job.title or s.title,
            "thumbnail": job.thumbnail or s.thumbnail,
            "kind": s.kind,
            "quality": s.quality,
            "start": s.start,
            "end": s.end,
            "lang": s.lang,
            "embed_subs": s.embed_subs,
            "label": job.label,
            "size": job.size,
        }
    )


manager = JobManager(
    base_opts=_base_opts,
    friendly_error=_friendly_error,
    on_done=_record_history,
    workers=MAX_CONCURRENT_DOWNLOADS,
    max_filesize=MAX_FILESIZE_BYTES,
    max_duration=MAX_DURATION_SECONDS,
)


# ---------------------------------------------------------------- أدوات
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


def _quality_label(height: int) -> str:
    for h, label in QUALITY_LABELS.items():
        if height >= h:
            return f"{height}p ({label})"
    return f"{height}p"


def _best_thumb(entry: dict) -> str | None:
    if entry.get("thumbnail"):
        return entry["thumbnail"]
    thumbs = entry.get("thumbnails") or []
    return thumbs[-1].get("url") if thumbs else None


def _subtitle_options(info: dict) -> list[dict]:
    out: list[dict] = []
    seen: set[str] = set()
    for code in (info.get("subtitles") or {}):
        if code == "live_chat" or code in seen:
            continue
        seen.add(code)
        out.append({"code": code, "name": LANG_NAMES.get(code.split("-")[0], code), "auto": False})
    auto = info.get("automatic_captions") or {}
    # الترجمة التلقائية فيها مئات اللغات، نعرض المهم بس
    for code in auto:
        base = code.split("-")[0]
        if code in seen or not (code.endswith("-orig") or code in ("ar", "en")):
            continue
        seen.add(code)
        name = LANG_NAMES.get(base, base)
        out.append({"code": code, "name": f"{name} (تلقائية)", "auto": True})
    return out


def _summarize_video(info: dict) -> dict:
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
        "type": "video",
        "title": info.get("title") or "فيديو",
        "thumbnail": _best_thumb(info),
        "duration": info.get("duration"),
        "uploader": info.get("uploader") or info.get("channel"),
        "site": info.get("extractor_key"),
        "webpage_url": info.get("webpage_url"),
        "options": options,
        "subtitles": _subtitle_options(info),
        "has_thumbnail": bool(_best_thumb(info)),
    }


def _summarize_playlist(info: dict) -> dict:
    entries = []
    for e in info.get("entries") or []:
        if not e:
            continue
        url = e.get("webpage_url") or e.get("url")
        if not url:
            continue
        if not url.startswith("http") and e.get("ie_key") == "Youtube":
            url = f"https://www.youtube.com/watch?v={url}"
        entries.append(
            {
                "url": url,
                "title": e.get("title") or "فيديو",
                "duration": e.get("duration"),
                "thumbnail": _best_thumb(e),
            }
        )
        if len(entries) >= MAX_PLAYLIST_ITEMS:
            break
    return {
        "type": "playlist",
        "title": info.get("title") or "قائمة تشغيل",
        "uploader": info.get("uploader") or info.get("channel"),
        "site": info.get("extractor_key"),
        "count": len(entries),
        "total": info.get("playlist_count") or len(entries),
        "entries": entries,
    }


def _extract(url: str) -> dict:
    opts = _base_opts()
    opts["extract_flat"] = "in_playlist"
    opts["playlistend"] = MAX_PLAYLIST_ITEMS
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    if info.get("_type") in ("playlist", "multi_video"):
        entries = [e for e in info.get("entries") or [] if e]
        if not entries:
            raise HTTPException(400, "ما فيه فيديوهات في هذا الرابط.")
        return _summarize_playlist(info)
    return _summarize_video(info)


# ---------------------------------------------------------------- معلومات الفيديو
class InfoRequest(BaseModel):
    url: str


@app.post("/api/info")
async def video_info(body: InfoRequest, request: Request) -> JSONResponse:
    _check_rate_limit(request)
    url = _validate_url(body.url)
    try:
        data = await asyncio.to_thread(_extract, url)
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, _friendly_error(exc)) from exc
    return JSONResponse(data)


# ---------------------------------------------------------------- المهام
class JobItem(BaseModel):
    url: str
    kind: str = "video"
    quality: str = "best"
    start: float | None = Field(default=None, ge=0)
    end: float | None = Field(default=None, gt=0)
    lang: str | None = Field(default=None, max_length=20)
    title: str | None = Field(default=None, max_length=300)
    thumbnail: str | None = Field(default=None, max_length=2000)
    embed_subs: str | None = Field(default=None, max_length=20)


class JobsRequest(BaseModel):
    items: list[JobItem]


def _to_spec(item: JobItem) -> JobSpec:
    url = _validate_url(item.url)
    if item.kind not in ("video", "audio", "thumb", "subs"):
        raise HTTPException(400, "نوع التحميل غير صحيح.")
    quality = item.quality
    if item.kind == "video" and quality != "best" and not quality.isdigit():
        raise HTTPException(400, "الجودة غير صحيحة.")
    if item.kind == "subs" and not item.lang:
        raise HTTPException(400, "اختر لغة الترجمة.")
    if item.start is not None and item.end is not None and item.end <= item.start:
        raise HTTPException(400, "وقت النهاية لازم يكون بعد البداية.")
    thumb = item.thumbnail if item.thumbnail and item.thumbnail.startswith("http") else None
    return JobSpec(
        url=url, kind=item.kind, quality=quality, start=item.start, end=item.end,
        lang=item.lang, title=item.title, thumbnail=thumb,
        embed_subs=item.embed_subs if item.kind == "video" else None,
    )


@app.post("/api/jobs")
async def create_jobs(body: JobsRequest, request: Request) -> JSONResponse:
    _check_rate_limit(request)
    if not body.items:
        raise HTTPException(400, "ما فيه روابط.")
    if len(body.items) > MAX_JOBS_PER_REQUEST:
        raise HTTPException(400, f"أقصى عدد {MAX_JOBS_PER_REQUEST} رابط بالمرة.")
    specs = [_to_spec(i) for i in body.items]
    jobs = [manager.submit(s) for s in specs]
    return JSONResponse({"jobs": [j.public() for j in jobs]})


@app.get("/api/jobs")
async def list_jobs() -> dict:
    return {"jobs": [j.public() for j in manager.list()]}


@app.get("/api/jobs/{job_id}")
async def get_job(job_id: str) -> dict:
    job = manager.get(job_id)
    if not job:
        raise HTTPException(404, "المهمة غير موجودة.")
    return job.public()


@app.post("/api/jobs/{job_id}/cancel")
async def cancel_job(job_id: str) -> dict:
    if not manager.cancel(job_id):
        raise HTTPException(404, "المهمة غير موجودة.")
    return {"ok": True}


@app.delete("/api/jobs/{job_id}")
async def delete_job(job_id: str) -> dict:
    if not manager.remove(job_id):
        raise HTTPException(404, "المهمة غير موجودة.")
    return {"ok": True}


@app.delete("/api/jobs")
async def clear_jobs() -> dict:
    manager.clear_finished()
    return {"ok": True}


@app.get("/api/jobs/{job_id}/file")
async def job_file(job_id: str):
    job = manager.get(job_id)
    if not job or job.status != "done" or not job.path or not Path(job.path).exists():
        raise HTTPException(404, "الملف غير جاهز أو انحذف. حمّله مرة ثانية.")
    headers = {"Content-Disposition": f"attachment; filename*=UTF-8''{quote(job.filename or 'video')}"}
    return FileResponse(job.path, headers=headers, media_type="application/octet-stream")


# ---------------------------------------------------------------- السجل
@app.get("/api/history")
async def get_history() -> dict:
    return {"items": history.all()}


@app.delete("/api/history/{item_id}")
async def delete_history_item(item_id: str) -> dict:
    if not history.delete(item_id):
        raise HTTPException(404, "غير موجود.")
    return {"ok": True}


@app.delete("/api/history")
async def clear_history() -> dict:
    history.clear()
    return {"ok": True}


# ---------------------------------------------------------------- تطبيق الصور (الماك)
PHOTOS_APP = Path("/System/Applications/Photos.app")
PHOTOS_SUPPORTED = sys.platform == "darwin" and PHOTOS_APP.exists()


def _import_to_photos(path: str) -> None:
    """يضيف الملف لتطبيق الصور في الماك (ولو صور iCloud شغالة، يوصل للآيفون بعد)."""
    script = (
        "on run argv\n"
        "  set f to (POSIX file (item 1 of argv)) as alias\n"
        '  tell application "Photos" to import {f} skip check duplicates true\n'
        "end run"
    )
    result = subprocess.run(["osascript", "-e", script, path], capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        err = result.stderr.lower()
        if "not allowed" in err or "-1743" in err or "not authorized" in err:
            raise HTTPException(403, "الماك ما سمح. افتح إعدادات النظام ← الخصوصية والأمان ← الأتمتة، وفعّل (الصور) للـ Terminal.")
        raise HTTPException(400, "تطبيق الصور ما قبل الملف. جودات 2K و4K و8K بصيغة VP9 أحياناً ما يقبلها، جرّب 1080p.")


@app.post("/api/jobs/{job_id}/photos")
async def save_to_photos(job_id: str) -> dict:
    if not PHOTOS_SUPPORTED:
        raise HTTPException(400, "الحفظ في الصور يشتغل لما يكون الموقع شغال على ماك.")
    job = manager.get(job_id)
    if not job or job.status != "done" or not job.path or not Path(job.path).exists():
        raise HTTPException(404, "الملف غير جاهز أو انحذف. حمّله مرة ثانية.")
    if job.spec.kind not in ("video", "thumb"):
        raise HTTPException(400, "تطبيق الصور يقبل الفيديو والصور بس.")
    await asyncio.to_thread(_import_to_photos, job.path)
    return {"ok": True}


@app.get("/api/health")
async def health(request: Request) -> dict:
    active = sum(1 for j in manager.list() if j.status in ACTIVE_STATES)
    host = request.client.host if request.client else ""
    return {
        "photos": PHOTOS_SUPPORTED,
        "local": host in ("127.0.0.1", "::1", "localhost"),
        "ok": True,
        "yt_dlp": yt_dlp.version.__version__,
        "ffmpeg": bool(shutil.which("ffmpeg") or FFMPEG_LOCATION),
        "active_jobs": active,
    }


app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
