"""سجل التحميلات: ينحفظ في ملف JSON على الجهاز نفسه."""

from __future__ import annotations

import json
import threading
import time
import uuid
from pathlib import Path

MAX_ITEMS = 500


class History:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def _read(self) -> list[dict]:
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return []

    def _write(self, items: list[dict]) -> None:
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(self.path)

    def all(self) -> list[dict]:
        with self.lock:
            return self._read()

    def add(self, entry: dict) -> None:
        with self.lock:
            items = self._read()
            entry = {"id": uuid.uuid4().hex[:12], "date": time.time(), **entry}
            items.insert(0, entry)
            self._write(items[:MAX_ITEMS])

    def delete(self, item_id: str) -> bool:
        with self.lock:
            items = self._read()
            new = [i for i in items if i.get("id") != item_id]
            if len(new) == len(items):
                return False
            self._write(new)
            return True

    def clear(self) -> None:
        with self.lock:
            self._write([])
