const $ = (id) => document.getElementById(id);
const form = $("form"), urlInput = $("url"), goBtn = $("go"), statusEl = $("status");
const result = $("result");

function setStatus(msg, isError = false) {
  statusEl.textContent = msg;
  statusEl.classList.toggle("error", isError);
}

function fmtDuration(s) {
  if (!s) return "";
  s = Math.round(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
}

function fmtSize(b) {
  if (!b) return "";
  const mb = b / 1024 / 1024;
  return mb >= 1024 ? `~${(mb / 1024).toFixed(1)} GB` : `~${Math.round(mb)} MB`;
}

async function fetchInfo(url) {
  const res = await fetch("api/info", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail || "صار خطأ، حاول مرة ثانية.");
  return data;
}

function render(info, url) {
  $("thumb").src = info.thumbnail || "";
  $("thumb").hidden = !info.thumbnail;
  $("title").textContent = info.title;
  $("sub").textContent = [info.uploader, info.site, fmtDuration(info.duration)].filter(Boolean).join(" · ");

  const box = $("options");
  box.replaceChildren();
  for (const opt of info.options) {
    const a = document.createElement("a");
    a.className = "opt" + (opt.type === "video" && Number(opt.id) >= 1440 ? " hi" : "");
    a.href = `api/download?url=${encodeURIComponent(url)}&quality=${encodeURIComponent(opt.id)}`;
    a.rel = "nofollow";
    const strong = document.createElement("strong");
    strong.textContent = opt.label;
    const span = document.createElement("span");
    const hi = opt.type === "video" && Number(opt.id) >= 1440;
    span.textContent = opt.type === "audio" ? "MP3"
      : ["MP4", fmtSize(opt.approx_size), hi ? "يشتغل على VLC" : ""].filter(Boolean).join(" · ");
    a.append(strong, span);
    a.addEventListener("click", () => setStatus("جاري تجهيز الملف على السيرفر... التحميل بيبدأ تلقائياً."));
    box.append(a);
  }
  result.hidden = false;
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const url = urlInput.value.trim();
  if (!url) return;
  goBtn.disabled = true;
  result.hidden = true;
  setStatus("جاري البحث عن الفيديو...");
  try {
    const info = await fetchInfo(url);
    render(info, url);
    setStatus("");
  } catch (err) {
    setStatus(err.message, true);
  } finally {
    goBtn.disabled = false;
  }
});

$("paste").addEventListener("click", async () => {
  try {
    urlInput.value = (await navigator.clipboard.readText()).trim();
    urlInput.focus();
  } catch {
    setStatus("المتصفح ما سمح باللصق، الصق الرابط يدوياً.", true);
  }
});
