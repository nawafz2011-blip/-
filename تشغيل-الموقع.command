#!/bin/bash
# اضغط على هذا الملف مرتين لتشغيل الموقع على جهاز الماك.

cd "$(dirname "$0")" || exit 1
clear
echo "======================================"
echo "        محمّل الفيديوهات"
echo "======================================"
echo

# 1) التأكد من وجود Python 3.10 أو أحدث
PY=""
for c in python3.13 python3.12 python3.11 python3.10 python3; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
    PY="$c"; break
  fi
done

if [ -z "$PY" ]; then
  echo "لازم تثبّت برنامج Python أول."
  echo "بيفتح لك موقع التحميل الحين: نزّل النسخة الأخيرة وثبّتها،"
  echo "وبعدها ارجع واضغط على هذا الملف مرة ثانية."
  open "https://www.python.org/downloads/macos/"
  echo
  read -r -p "اضغط Enter للإغلاق..."
  exit 1
fi

# 2) تجهيز المكتبات (أول مرة بس تاخذ دقيقة أو دقيقتين)
if [ ! -x ".venv/bin/python" ]; then
  echo "أول تشغيل: جاري تجهيز الموقع... (دقيقة أو دقيقتين)"
  "$PY" -m venv .venv || { read -r -p "صار خطأ. اضغط Enter للإغلاق..."; exit 1; }
fi

echo "جاري التحديث..."
.venv/bin/python -m pip install -q --disable-pip-version-check -U pip >/dev/null 2>&1
if ! .venv/bin/python -m pip install -q --disable-pip-version-check -U -r requirements.txt; then
  echo
  echo "ما قدرنا نثبّت المكتبات. تأكد إن الإنترنت شغال وجرب مرة ثانية."
  read -r -p "اضغط Enter للإغلاق..."
  exit 1
fi

# 3) تشغيل الموقع وفتحه في المتصفح
PORT=8000
while lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; do PORT=$((PORT + 1)); done

echo
echo "الموقع شغال على: http://localhost:$PORT"
echo "خل هذي النافذة مفتوحة طول ما تستخدم الموقع. لإيقافه سكّر النافذة."
echo

# نفتح المتصفح بعد ما يجهز السيرفر
(for _ in $(seq 1 60); do
   curl -s -o /dev/null "http://127.0.0.1:$PORT/api/health" && { open "http://localhost:$PORT"; exit 0; }
   sleep 1
 done) &
exec .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port "$PORT"
