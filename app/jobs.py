"""مهام التحميل: طابور، تقدّم، إلغاء، وتنظيف الملفات القديمة."""

from __future__ import annotations

import shutil
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable

import yt_dlp

# الحالات: queued → preparing → downloading → processing → done | error | canceled
ACTIVE_STATES = {"queued", "preparing", "downloading", "processing"}


class JobCanceled(Exception):
    pass


@dataclass
class JobSpec:
    url: str
    kind: str = "video"  # video | audio | thumb | subs
    quality: str = "best"  # ارتفاع مثل "1080" أو "best"
    start: float | None = None  # قص: بداية بالثواني
    end: float | None = None  # قص: نهاية بالثواني
    lang: str | None = None  # لغة الترجمة
    title: str | None = None
    thumbnail: str | None = None
    embed_subs: str | None = None  # لغة ترجمة تنحط داخل ملف الفيديو


@dataclass
class Job:
    id: str
    spec: JobSpec
    label: str
    status: str = "queued"
    title: str | None = None
    thumbnail: str | None = None
    progress: float | None = None  # من 0 إلى 1، أو None إذا غير معروف
    speed: float | None = None  # بايت/ثانية
    eta: float | None = None  # ثواني
    downloaded: int = 0
    error: str | None = None
    filename: str | None = None
    size: int | None = None
    created: float = field(default_factory=time.time)
    finished_at: float | None = None
    # داخلي
    workdir: str | None = None
    path: str | None = None
    cancel: bool = False
    expected: int | None = None
    finished_bytes: int = 0

    def public(self) -> dict:
        d = asdict(self)
        spec = d.pop("spec")
        for k in ("workdir", "path", "cancel", "expected", "finished_bytes"):
            d.pop(k)
        d.update(
            url=spec["url"],
            kind=spec["kind"],
            quality=spec["quality"],
            start=spec["start"],
            end=spec["end"],
            lang=spec["lang"],
            embed_subs=spec["embed_subs"],
            title=self.title or spec["title"],
            thumbnail=self.thumbnail or spec["thumbnail"],
        )
        return d


def fmt_time(sec: float | None) -> str:
    if sec is None:
        return ""
    sec = int(sec)
    h, m, s = sec // 3600, (sec % 3600) // 60, sec % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def make_label(spec: JobSpec) -> str:
    if spec.kind == "audio":
        label = "صوت MP3"
    elif spec.kind == "thumb":
        label = "صورة الغلاف"
    elif spec.kind == "subs":
        label = f"ترجمة ({spec.lang})"
    else:
        label = "أفضل جودة" if spec.quality == "best" else f"{spec.quality}p"
        if spec.embed_subs:
            label += " · ترجمة"
    if spec.kind in ("video", "audio") and (spec.start is not None or spec.end is not None):
        label += f" · مقطع {fmt_time(spec.start or 0)}–{fmt_time(spec.end) or 'النهاية'}"
    return label


class JobManager:
    def __init__(
        self,
        base_opts: Callable[[], dict],
        friendly_error: Callable[[Exception], str],
        on_done: Callable[[Job], None],
        workers: int = 3,
        ttl_seconds: int = 2 * 60 * 60,
        max_filesize: int | None = None,
        max_duration: int | None = None,
    ) -> None:
        self.base_opts = base_opts
        self.friendly_error = friendly_error
        self.on_done = on_done
        self.ttl = ttl_seconds
        self.max_filesize = max_filesize
        self.max_duration = max_duration
        self.jobs: dict[str, Job] = {}
        self.lock = threading.Lock()
        self.pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="dl")
        threading.Thread(target=self._cleanup_loop, daemon=True).start()

    # ---------- واجهة ----------
    def submit(self, spec: JobSpec) -> Job:
        job = Job(id=uuid.uuid4().hex[:12], spec=spec, label=make_label(spec), title=spec.title, thumbnail=spec.thumbnail)
        with self.lock:
            self.jobs[job.id] = job
        self.pool.submit(self._run, job)
        return job

    def get(self, job_id: str) -> Job | None:
        return self.jobs.get(job_id)

    def list(self) -> list[Job]:
        with self.lock:
            return sorted(self.jobs.values(), key=lambda j: j.created)

    def cancel(self, job_id: str) -> bool:
        job = self.jobs.get(job_id)
        if not job:
            return False
        job.cancel = True
        if job.status == "queued":
            job.status = "canceled"
            job.finished_at = time.time()
        return True

    def remove(self, job_id: str) -> bool:
        job = self.jobs.get(job_id)
        if not job:
            return False
        self.cancel(job_id)
        if job.status not in ACTIVE_STATES:
            self._delete(job)
        return True

    def clear_finished(self) -> None:
        for job in list(self.jobs.values()):
            if job.status not in ACTIVE_STATES:
                self._delete(job)

    # ---------- التنفيذ ----------
    def _opts_for(self, job: Job) -> dict:
        spec = job.spec
        opts = self.base_opts()
        opts.update(
            {
                "outtmpl": str(Path(job.workdir) / "%(title).150B.%(ext)s"),
                "progress_hooks": [lambda d: self._progress(job, d)],
                "postprocessor_hooks": [lambda d: self._postprocess(job, d)],
                "concurrent_fragment_downloads": 4,
                "noprogress": True,
            }
        )
        if self.max_filesize:
            opts["max_filesize"] = self.max_filesize
        if self.max_duration and spec.kind in ("video", "audio"):
            opts["match_filter"] = yt_dlp.utils.match_filter_func(f"!is_live & duration <=? {self.max_duration}")

        if spec.kind == "audio":
            opts["format"] = "bestaudio/best"
            opts["postprocessors"] = [{"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"}]
        elif spec.kind == "video":
            res = int(spec.quality) if spec.quality.isdigit() else None
            opts["format"] = "bv*+ba/b"
            # نفضّل H.264 + AAC لأنها تشتغل على كل الأجهزة. 4K و8K ما لها H.264 في يوتيوب.
            opts["format_sort"] = ([f"res:{res}"] if res else ["res"]) + ["vcodec:h264", "acodec:aac", "ext:mp4:m4a"]
            opts["merge_output_format"] = "mp4"
            if spec.embed_subs:
                # الترجمة تنحط داخل ملف الفيديو، وتقدر تشغّلها أو تطفيها من المشغّل
                opts["writesubtitles"] = True
                opts["writeautomaticsub"] = True
                opts["subtitleslangs"] = [spec.embed_subs]
                opts["subtitlesformat"] = "srt/vtt/best"
                opts["postprocessors"] = [{"key": "FFmpegEmbedSubtitle", "already_have_subtitle": False}]
        elif spec.kind == "thumb":
            opts["skip_download"] = True
            opts["writethumbnail"] = True
            opts["postprocessors"] = [{"key": "FFmpegThumbnailsConvertor", "format": "jpg", "when": "before_dl"}]
        elif spec.kind == "subs":
            opts["skip_download"] = True
            opts["writesubtitles"] = True
            opts["writeautomaticsub"] = True
            opts["subtitleslangs"] = [spec.lang or "ar"]
            opts["subtitlesformat"] = "srt/vtt/best"
            opts["postprocessors"] = [{"key": "FFmpegSubtitlesConvertor", "format": "srt", "when": "before_dl"}]

        if spec.kind in ("video", "audio") and (spec.start is not None or spec.end is not None):
            start = spec.start or 0
            end = spec.end if spec.end is not None else float("inf")
            opts["download_ranges"] = yt_dlp.utils.download_range_func(None, [(start, end)])
            opts["force_keyframes_at_cuts"] = True
        return opts

    def _run(self, job: Job) -> None:
        if job.cancel:
            return
        job.status = "preparing"
        job.workdir = tempfile.mkdtemp(prefix="job_")
        try:
            with yt_dlp.YoutubeDL(self._opts_for(job)) as ydl:
                info = ydl.extract_info(job.spec.url, download=False)
                if job.cancel:
                    raise JobCanceled()
                if info.get("_type") == "playlist":
                    entries = [e for e in info.get("entries") or [] if e]
                    if not entries:
                        raise ValueError("ما فيه فيديو في هذا الرابط")
                    info = entries[0]
                job.title = info.get("title") or job.title
                job.thumbnail = job.thumbnail or info.get("thumbnail")
                job.expected = self._expected_size(info)
                job.status = "downloading"
                ydl.process_ie_result(info, download=True)

            if job.cancel:
                raise JobCanceled()
            path = self._find_output(job)
            if not path:
                raise ValueError("الفيديو أطول أو أكبر من المسموح، أو بث مباشر، أو ما فيه ترجمة بهذي اللغة.")
            job.path = str(path)
            job.filename = path.name
            job.size = path.stat().st_size
            job.progress = 1.0
            job.eta = 0
            job.status = "done"
            job.finished_at = time.time()
            try:
                self.on_done(job)
            except Exception:  # noqa: BLE001
                pass
        except Exception as exc:  # noqa: BLE001
            job.finished_at = time.time()
            if job.cancel:
                job.status = "canceled"
            else:
                job.status = "error"
                job.error = str(exc) if isinstance(exc, ValueError) else self.friendly_error(exc)
            if job.workdir:
                shutil.rmtree(job.workdir, ignore_errors=True)

    @staticmethod
    def _expected_size(info: dict) -> int | None:
        formats = info.get("requested_formats") or [info]
        total = 0
        for f in formats:
            size = f.get("filesize") or f.get("filesize_approx")
            if not size:
                return None
            total += int(size)
        return total or None

    def _find_output(self, job: Job) -> Path | None:
        wd = Path(job.workdir)
        files = [p for p in wd.iterdir() if p.is_file() and not p.name.endswith((".part", ".ytdl", ".temp"))]
        if job.spec.kind == "subs":
            files = [p for p in files if p.suffix in (".srt", ".vtt", ".ass", ".ttml")]
        elif job.spec.kind == "thumb":
            files = [p for p in files if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp")]
        return max(files, key=lambda p: p.stat().st_size) if files else None

    def _progress(self, job: Job, d: dict) -> None:
        if job.cancel:
            raise JobCanceled()
        status = d.get("status")
        if status == "downloading":
            job.status = "downloading"
            done = int(d.get("downloaded_bytes") or 0)
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            job.speed = d.get("speed")
            overall_done = job.finished_bytes + done
            job.downloaded = overall_done
            if job.expected and not (job.spec.start is not None or job.spec.end is not None):
                job.progress = min(overall_done / job.expected, 0.99)
                job.eta = (job.expected - overall_done) / job.speed if job.speed else None
            elif total:
                job.progress = min(done / total, 0.99)
                job.eta = d.get("eta")
            else:
                job.progress = None
                job.eta = None
        elif status == "finished":
            job.finished_bytes += int(d.get("total_bytes") or d.get("downloaded_bytes") or 0)

    def _postprocess(self, job: Job, d: dict) -> None:
        if job.cancel:
            raise JobCanceled()
        if d.get("status") == "started" and job.spec.kind in ("video", "audio"):
            job.status = "processing"
            job.speed = None
            job.eta = None

    # ---------- تنظيف ----------
    def _delete(self, job: Job) -> None:
        with self.lock:
            self.jobs.pop(job.id, None)
        if job.workdir:
            shutil.rmtree(job.workdir, ignore_errors=True)

    def _cleanup_loop(self) -> None:
        while True:
            time.sleep(300)
            now = time.time()
            for job in list(self.jobs.values()):
                if job.status not in ACTIVE_STATES and job.finished_at and now - job.finished_at > self.ttl:
                    self._delete(job)
