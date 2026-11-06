"use strict";

const $ = (id) => document.getElementById(id);
const statusEl = $("status");
const urlInput = $("url");

const PRESETS = [
  ["best", "أفضل جودة متوفرة"],
  ["2160", "4K (2160p)"],
  ["1440", "2K (1440p)"],
  ["1080", "Full HD (1080p)"],
  ["720", "HD (720p)"],
  ["480", "480p"],
  ["360", "360p"],
  ["audio", "صوت فقط (MP3)"],
];
const ACTIVE = new Set(["queued", "preparing", "downloading", "processing"]);

let current = null; // آخر نتيجة بحث
let jobs = new Map();
const autoSave = new Set(); // المهام اللي تنحفظ تلقائياً أول ما تخلص
let pollTimer = null;

/* ---------------------------------------------------------------- أدوات */
function setStatus(msg, isError = false) {
  statusEl.textContent = msg;
  statusEl.classList.toggle("error", isError);
}

function fmtDuration(s) {
  if (s == null || s === "") return "";
  s = Math.round(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
}

function fmtSize(b) {
  if (!b) return "";
  const mb = b / 1024 / 1024;
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  if (mb >= 1) return `${Math.round(mb)} MB`;
  return `${Math.max(1, Math.round(b / 1024))} KB`;
}

function fmtSpeed(bps) {
  return bps ? `${fmtSize(bps)}/s` : "";
}

/** يعزل الأرقام والكلمات الإنجليزية عشان ما تنقلب داخل النص العربي. */
function iso(text) {
  return String(text ?? "").replace(/[A-Za-z0-9~][A-Za-z0-9:.%\/~()–\- ]*[A-Za-z0-9%)]|[A-Za-z0-9]/g, (m) => "\u2066" + m.replace(/–/g, "\u2060–\u2060") + "\u2069");
}

function fmtDate(ts) {
  try {
    return new Date(ts * 1000).toLocaleString("ar", { dateStyle: "medium", timeStyle: "short" });
  } catch { return ""; }
}

/** "1:20" → 80 ، "90" → 90 ، "1:02:03" → 3723 */
function parseTime(text) {
  text = (text || "").trim().replace(/[٠-٩]/g, (d) => "٠١٢٣٤٥٦٧٨٩".indexOf(d));
  if (!text) return null;
  if (!/^\d+(:\d{1,2}){0,2}$/.test(text)) return NaN;
  return text.split(":").reduce((acc, p) => acc * 60 + Number(p), 0);
}

function extractUrls(text) {
  const found = (text || "").match(/https?:\/\/[^\s"'<>]+/g) || [];
  return [...new Set(found.map((u) => u.replace(/[.,)]+$/, "")))];
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: options.body ? { "Content-Type": "application/json" } : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const detail = Array.isArray(data.detail) ? "البيانات غير صحيحة." : data.detail;
    throw new Error(detail || "صار خطأ، حاول مرة ثانية.");
  }
  return data;
}

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.assign(node, props);
  for (const c of children) if (c != null) node.append(c);
  return node;
}

function fillPresets(select) {
  select.replaceChildren(...PRESETS.map(([v, t]) => el("option", { value: v, textContent: t })));
  select.value = "1080";
}

function presetToItem(value) {
  return value === "audio" ? { kind: "audio" } : { kind: "video", quality: value };
}

/* ---------------------------------------------------------------- التبويبات */
function showView(name) {
  for (const v of ["download", "history"]) $("view-" + v).hidden = v !== name;
  document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t.dataset.view === name));
  if (name === "history") loadHistory();
  window.scrollTo({ top: 0 });
}
document.querySelectorAll("[data-view]").forEach((b) =>
  b.addEventListener("click", (e) => { e.preventDefault(); showView(b.dataset.view); })
);

/* ---------------------------------------------------------------- البحث */
function autoGrow() {
  urlInput.style.height = "auto";
  urlInput.style.height = Math.min(urlInput.scrollHeight, 180) + "px";
}
urlInput.addEventListener("input", autoGrow);
urlInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); $("form").requestSubmit(); }
});

function hideResults() {
  $("single").hidden = true;
  $("playlist").hidden = true;
  $("batch").hidden = true;
}

$("form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const urls = extractUrls(urlInput.value);
  hideResults();
  if (!urls.length) return setStatus("الصق رابط صحيح يبدأ بـ https://", true);

  if (urls.length > 1) return showBatch(urls);

  const go = $("go");
  go.disabled = true;
  setStatus("جاري البحث...");
  try {
    current = await api("api/info", { method: "POST", body: JSON.stringify({ url: urls[0] }) });
    current.url = urls[0];
    if (current.type === "playlist") showPlaylist(current); else showSingle(current);
    setStatus("");
  } catch (err) {
    setStatus(err.message, true);
  } finally {
    go.disabled = false;
  }
});

$("paste").addEventListener("click", async () => {
  try {
    urlInput.value = (await navigator.clipboard.readText()).trim();
    autoGrow();
    if (extractUrls(urlInput.value).length) $("form").requestSubmit();
  } catch {
    setStatus("المتصفح ما سمح باللصق، الصق الرابط يدوياً.", true);
    urlInput.focus();
  }
});

/* ---------------------------------------------------------------- فيديو واحد */
function trimRange() {
  const start = parseTime($("trimStart").value);
  const end = parseTime($("trimEnd").value);
  if (Number.isNaN(start) || Number.isNaN(end)) throw new Error("اكتب الوقت بهالشكل: 1:20");
  if (start != null && end != null && end <= start) throw new Error("وقت النهاية لازم يكون بعد البداية.");
  if (current?.duration && start != null && start >= current.duration) throw new Error("وقت البداية بعد نهاية الفيديو.");
  return { start: start || null, end: end ?? null };
}

function updateTrimState() {
  const on = $("trimStart").value.trim() || $("trimEnd").value.trim();
  document.querySelector(".trim").classList.toggle("active", !!on);
}
$("trimStart").addEventListener("input", updateTrimState);
$("trimEnd").addEventListener("input", updateTrimState);
$("trimClear").addEventListener("click", () => {
  $("trimStart").value = ""; $("trimEnd").value = ""; updateTrimState();
});

function showSingle(info) {
  $("thumb").hidden = !info.thumbnail;
  if (info.thumbnail) $("thumb").src = info.thumbnail;
  $("title").textContent = info.title;
  $("sub").textContent = iso([info.uploader, info.site, fmtDuration(info.duration)].filter(Boolean).join(" · "));
  $("trimStart").value = ""; $("trimEnd").value = ""; updateTrimState();
  $("trimEnd").placeholder = info.duration ? fmtDuration(info.duration) : "النهاية";

  const box = $("options");
  box.replaceChildren();
  for (const opt of info.options) {
    const hi = opt.type === "video" && Number(opt.id) >= 1440;
    const detail = opt.type === "audio" ? "MP3"
      : ["MP4", opt.approx_size ? "~" + fmtSize(opt.approx_size) : "", hi ? "يشتغل على VLC" : ""].filter(Boolean).join(" · ");
    const btn = el("button", { type: "button", className: "opt" + (hi ? " hi" : "") },
      el("strong", { textContent: opt.label }), el("span", { textContent: iso(detail) }));
    btn.addEventListener("click", () => {
      try {
        const range = trimRange();
        const item = opt.type === "audio" ? { kind: "audio" } : { kind: "video", quality: opt.id, embed_subs: embedLang() };
        startJobs([{ ...base(info), ...item, ...range }], true);
      } catch (err) {
        $("extras").open = true;
        setStatus(err.message, true);
      }
    });
    box.append(btn);
  }

  const subs = info.subtitles || [];
  $("subsLang").replaceChildren(...subs.map((s) => el("option", { value: s.code, textContent: s.name })));
  const ar = subs.find((s) => s.code.startsWith("ar"));
  if (ar) $("subsLang").value = ar.code;
  $("subsLang").hidden = $("subsMode").hidden = !subs.length;
  $("noSubs").hidden = !!subs.length;
  document.querySelector('input[name="subsMode"][value="none"]').checked = true;
  updateSubsMode();
  $("thumbBlock").hidden = !info.has_thumbnail;
  $("extras").open = false;

  $("single").hidden = false;
}

function base(info) {
  return { url: info.webpage_url || info.url || current.url, title: info.title, thumbnail: info.thumbnail || null };
}

function subsMode() {
  return document.querySelector('input[name="subsMode"]:checked')?.value || "none";
}
function embedLang() {
  return subsMode() === "embed" && current?.subtitles?.length ? $("subsLang").value : null;
}
function updateSubsMode() {
  const mode = subsMode();
  const hint = $("subsHint");
  hint.hidden = mode === "none" || !(current?.subtitles?.length);
  hint.textContent = mode === "embed"
    ? "اختر الجودة من فوق، والترجمة بتنحط داخل الفيديو. تقدر تشغّلها أو تطفيها من المشغّل (VLC مثلاً)."
    : "الترجمة بتنزل ملف لحالها (SRT)، بدون الفيديو.";
  $("subsBtn").hidden = mode !== "file" || !(current?.subtitles?.length);
}
document.querySelectorAll('input[name="subsMode"]').forEach((r) => r.addEventListener("change", updateSubsMode));

$("subsBtn").addEventListener("click", () => {
  startJobs([{ ...base(current), kind: "subs", lang: $("subsLang").value }], true);
});
$("thumbBtn").addEventListener("click", () => {
  startJobs([{ ...base(current), kind: "thumb" }], true);
});

/* ---------------------------------------------------------------- قائمة تشغيل */
function showPlaylist(info) {
  $("plTitle").textContent = info.title;
  const more = info.total > info.count ? ` (أول ${info.count} من ${info.total})` : "";
  $("plSub").textContent = iso([info.uploader, info.site, `${info.count} فيديو${more}`].filter(Boolean).join(" · "));
  const list = $("plList");
  list.replaceChildren();
  info.entries.forEach((entry, i) => {
    const cb = el("input", { type: "checkbox", checked: true });
    cb.dataset.index = i;
    cb.addEventListener("change", updatePlCount);
    const img = el("img", { alt: "", loading: "lazy", referrerPolicy: "no-referrer" });
    if (entry.thumbnail) img.src = entry.thumbnail; else img.style.visibility = "hidden";
    const li = el("li", { className: "pl-item" }, cb, img,
      el("span", { className: "t", textContent: entry.title }),
      el("span", { className: "d", textContent: fmtDuration(entry.duration) }));
    li.addEventListener("click", (e) => { if (e.target !== cb) { cb.checked = !cb.checked; updatePlCount(); } });
    list.append(li);
  });
  $("plAll").checked = true;
  updatePlCount();
  $("playlist").hidden = false;
}

function selectedEntries() {
  return [...$("plList").querySelectorAll("input:checked")].map((cb) => current.entries[cb.dataset.index]);
}

function updatePlCount() {
  const n = selectedEntries().length;
  $("plGo").textContent = n ? `حمّل المحدد (${n})` : "اختر فيديو";
  $("plGo").disabled = !n;
  $("plAll").checked = n === current.entries.length;
}

$("plAll").addEventListener("change", (e) => {
  $("plList").querySelectorAll("input").forEach((cb) => (cb.checked = e.target.checked));
  updatePlCount();
});

$("plGo").addEventListener("click", () => {
  const item = presetToItem($("plQuality").value);
  const items = selectedEntries().map((e) => ({ url: e.url, title: e.title, thumbnail: e.thumbnail, ...item }));
  startJobs(items, false);
});

/* ---------------------------------------------------------------- كذا رابط */
let batchUrls = [];
function showBatch(urls) {
  batchUrls = urls;
  $("batchTitle").textContent = `${urls.length} روابط`;
  $("batch").hidden = false;
  setStatus("");
}
$("batchGo").addEventListener("click", () => {
  const item = presetToItem($("batchQuality").value);
  startJobs(batchUrls.map((url) => ({ url, ...item })), false);
});

/* ---------------------------------------------------------------- المهام */
async function startJobs(items, save) {
  setStatus("");
  try {
    const { jobs: created } = await api("api/jobs", { method: "POST", body: JSON.stringify({ items }) });
    for (const j of created) {
      jobs.set(j.id, j);
      if (save) autoSave.add(j.id);
    }
    renderJobs();
    poll();
    $("queue").scrollIntoView({ behavior: "smooth", block: "start" });
    if (!save && created.length > 1) setStatus(`انضاف ${created.length} تحميل للقائمة. احفظ كل واحد لما يخلص.`);
  } catch (err) {
    setStatus(err.message, true);
  }
}

function saveFile(job) {
  const a = el("a", { href: `api/jobs/${job.id}/file`, download: job.filename || "" });
  document.body.append(a);
  a.click();
  a.remove();
}

function jobMeta(j) {
  switch (j.status) {
    case "queued": return "في الانتظار...";
    case "preparing": return "جاري التجهيز...";
    case "downloading": {
      const parts = [];
      if (j.progress != null) parts.push(Math.floor(j.progress * 100) + "%");
      else if (j.downloaded) parts.push(fmtSize(j.downloaded));
      if (j.speed) parts.push(fmtSpeed(j.speed));
      if (j.eta) parts.push("باقي " + fmtDuration(j.eta));
      return parts.join(" · ") || "جاري التحميل...";
    }
    case "processing": return j.kind === "audio" ? "جاري التحويل لـ MP3..." : "جاري دمج الصوت مع الصورة...";
    case "done": return "تم ✓ " + fmtSize(j.size);
    case "error": return j.error || "صار خطأ.";
    case "canceled": return "انلغى.";
  }
  return "";
}

function actionBtn(text, cls, onClick) {
  const b = el("button", { type: "button", className: "btn " + cls, textContent: text });
  b.addEventListener("click", onClick);
  return b;
}

function renderJobs() {
  const list = $("jobs");
  const all = [...jobs.values()].sort((a, b) => b.created - a.created);
  $("queue").hidden = !all.length;
  $("clearDone").hidden = !all.some((j) => !ACTIVE.has(j.status));

  const tpl = $("jobTpl");
  const nodes = all.map((j) => {
    let li = list.querySelector(`[data-id="${j.id}"]`);
    if (!li) {
      li = tpl.content.firstElementChild.cloneNode(true);
      li.dataset.id = j.id;
    }
    li.className = "job " + j.status;
    const img = li.querySelector(".job-thumb");
    if (j.thumbnail && img.getAttribute("src") !== j.thumbnail) img.src = j.thumbnail;
    img.style.visibility = j.thumbnail ? "visible" : "hidden";
    li.querySelector(".job-title").textContent = j.title || j.url;
    li.querySelector(".job-label").textContent = iso(j.label);

    const bar = li.querySelector(".bar");
    const indet = ACTIVE.has(j.status) && (j.progress == null || j.status !== "downloading");
    bar.classList.toggle("indet", indet);
    bar.hidden = j.status === "error" || j.status === "canceled";
    li.querySelector(".bar-fill").style.width = indet ? "" : Math.round((j.progress || 0) * 100) + "%";
    li.querySelector(".job-meta").textContent = iso(jobMeta(j));

    const actions = li.querySelector(".job-actions");
    const key = j.status;
    if (actions.dataset.key !== key) {
      actions.dataset.key = key;
      actions.replaceChildren();
      if (ACTIVE.has(j.status)) {
        actions.append(actionBtn("إلغاء", "ghost", () => api(`api/jobs/${j.id}/cancel`, { method: "POST" }).then(poll).catch(() => {})));
      } else {
        if (j.status === "done") actions.append(actionBtn("حفظ", "primary", () => saveFile(j)));
        if (j.status === "error" || j.status === "canceled") actions.append(actionBtn("إعادة", "ghost", () => retry(j)));
        actions.append(actionBtn("إزالة", "link", () => removeJob(j.id)));
      }
    }
    return li;
  });
  list.replaceChildren(...nodes);
}

function retry(j) {
  removeJob(j.id);
  startJobs([{ url: j.url, kind: j.kind, quality: j.quality, start: j.start, end: j.end, lang: j.lang, embed_subs: j.embed_subs, title: j.title, thumbnail: j.thumbnail }], true);
}

async function removeJob(id) {
  jobs.delete(id);
  renderJobs();
  api(`api/jobs/${id}`, { method: "DELETE" }).catch(() => {});
}

$("clearDone").addEventListener("click", async () => {
  for (const [id, j] of jobs) if (!ACTIVE.has(j.status)) jobs.delete(id);
  renderJobs();
  api("api/jobs", { method: "DELETE" }).catch(() => {});
});

async function poll() {
  clearTimeout(pollTimer);
  try {
    const { jobs: list } = await api("api/jobs");
    const prev = jobs;
    jobs = new Map(list.map((j) => [j.id, j]));
    for (const j of list) {
      if (j.status === "done" && autoSave.has(j.id)) {
        autoSave.delete(j.id);
        saveFile(j);
      }
      if (j.status === "done" && prev.get(j.id)?.status !== "done") historyDirty = true;
    }
    renderJobs();
    if (historyDirty) refreshHistoryCount();
    if (list.some((j) => ACTIVE.has(j.status))) pollTimer = setTimeout(poll, 1000);
  } catch {
    pollTimer = setTimeout(poll, 3000);
  }
}

/* ---------------------------------------------------------------- السجل */
let historyDirty = false;
let historyItems = [];

async function refreshHistoryCount() {
  historyDirty = false;
  try {
    historyItems = (await api("api/history")).items;
    const c = $("histCount");
    c.textContent = historyItems.length;
    c.hidden = !historyItems.length;
    if (!$("view-history").hidden) renderHistory();
  } catch { /* تجاهل */ }
}

async function loadHistory() {
  await refreshHistoryCount();
  renderHistory();
}

function renderHistory() {
  const list = $("histList");
  $("histEmpty").hidden = historyItems.length > 0;
  $("clearHistory").hidden = !historyItems.length;
  list.replaceChildren(...historyItems.map((h) => {
    const img = el("img", { className: "job-thumb", alt: "", loading: "lazy", referrerPolicy: "no-referrer" });
    if (h.thumbnail) img.src = h.thumbnail; else img.style.visibility = "hidden";
    const again = actionBtn("حمّل مرة ثانية", "primary", () => {
      showView("download");
      startJobs([{ url: h.url, kind: h.kind, quality: h.quality, start: h.start, end: h.end, lang: h.lang, embed_subs: h.embed_subs, title: h.title, thumbnail: h.thumbnail }], true);
    });
    const open = el("a", { className: "btn link", href: h.url, target: "_blank", rel: "noopener noreferrer", textContent: "الرابط" });
    const del = actionBtn("حذف", "link", async () => {
      historyItems = historyItems.filter((x) => x.id !== h.id);
      renderHistory();
      refreshBadge();
      api(`api/history/${h.id}`, { method: "DELETE" }).catch(() => {});
    });
    return el("li", { className: "job" }, img,
      el("div", { className: "job-body" },
        el("div", { className: "job-title", textContent: h.title || h.url }),
        el("div", { className: "job-label muted small", textContent: iso([h.label, fmtSize(h.size)].filter(Boolean).join(" · ")) }),
        el("div", { className: "job-meta small", textContent: fmtDate(h.date) })),
      el("div", { className: "job-actions" }, again, open, del));
  }));
}

function refreshBadge() {
  const c = $("histCount");
  c.textContent = historyItems.length;
  c.hidden = !historyItems.length;
}

$("clearHistory").addEventListener("click", async () => {
  if (!confirm("متأكد تبي تمسح السجل كامل؟")) return;
  historyItems = [];
  renderHistory();
  refreshBadge();
  api("api/history", { method: "DELETE" }).catch(() => {});
});

/* ---------------------------------------------------------------- البداية */
fillPresets($("plQuality"));
fillPresets($("batchQuality"));
poll();
refreshHistoryCount();
