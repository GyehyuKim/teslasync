#!/usr/bin/env python3
"""TeslaSync 클립 서버 — Phase 1 파일 목록/다운로드 API.

Android 앱("클립 브라우저")이 쓰는 최소 API. 표준 라이브러리만 사용 —
보드(Armbian)에 python3만 있으면 되고 pip 불필요.

    GET /api/events?limit=N   SavedClips/SentryClips 이벤트 목록 (JSON, 최신순)
    GET /files/<타입>/<이벤트>/<파일>   개별 파일 (mp4/thumb.png, Range 지원)

루트는 teslausb archive 공유(기본 /backingfiles/archive_share). 아카이브가
TeslaCam/ 하위에 쌓이는 배치도 자동 인식. RecentClips/EncryptedClips/Photobooth는
설계상 제외(PLAN.md) — EVENT_TYPES 화이트리스트가 그 역할.

실행:  python3 clipserver.py [--root DIR] [--port 8080] [--bind 0.0.0.0]
"""
import argparse
import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

EVENT_TYPES = ("SavedClips", "SentryClips")
CHUNK = 1 << 16
# 파일명 끝의 카메라 이름: 2026-06-24_16-16-01-front.mp4 → front
CAMERA_RE = re.compile(r"-([a-z_]+)$")
RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)$")


def find_type_dir(root, event_type):
    """archive 루트 직하 또는 TeslaCam/ 하위, 먼저 발견되는 쪽."""
    for cand in (os.path.join(root, event_type),
                 os.path.join(root, "TeslaCam", event_type)):
        if os.path.isdir(cand):
            return cand
    return None


def camera_of(filename):
    m = CAMERA_RE.search(filename[:-len(".mp4")])
    return m.group(1) if m else "unknown"


def scan_events(root, limit=100):
    events = []
    for event_type in EVENT_TYPES:
        type_dir = find_type_dir(root, event_type)
        if not type_dir:
            continue
        for name in os.listdir(type_dir):
            event_dir = os.path.join(type_dir, name)
            if not os.path.isdir(event_dir):
                continue
            clips = []
            for f in sorted(os.listdir(event_dir)):
                if not f.lower().endswith(".mp4"):
                    continue  # event.json/thumb.png 등 메타 파일은 클립이 아님
                try:
                    size = os.path.getsize(os.path.join(event_dir, f))
                except OSError:
                    continue
                clips.append({"file": f, "camera": camera_of(f), "size": size})
            if not clips:
                continue
            meta = {}
            try:
                with open(os.path.join(event_dir, "event.json"), encoding="utf-8") as fh:
                    meta = json.load(fh)
            except (OSError, ValueError):
                pass  # 메타가 없거나 깨져도 목록에는 나와야 함
            events.append({
                "type": event_type,
                "name": name,
                "timestamp": meta.get("timestamp", name),
                "city": meta.get("city", ""),
                "street": meta.get("street", ""),
                "reason": meta.get("reason", ""),
                "thumb": os.path.isfile(os.path.join(event_dir, "thumb.png")),
                "clips": clips,
            })
    # 폴더명이 YYYY-MM-DD_HH-MM-SS라 문자열 역순 = 최신순
    events.sort(key=lambda e: e["name"], reverse=True)
    return events[:limit]


def resolve_file(root, rel):
    """<타입>/<이벤트>/<파일> → 실제 경로. 화이트리스트 밖/탈출 경로는 None."""
    parts = [unquote(p) for p in rel.split("/")]
    if len(parts) != 3 or parts[0] not in EVENT_TYPES:
        return None
    type_dir = find_type_dir(root, parts[0])
    if not type_dir:
        return None
    path = os.path.realpath(os.path.join(type_dir, parts[1], parts[2]))
    if not path.startswith(os.path.realpath(type_dir) + os.sep):
        return None  # ../ 등으로 type_dir 밖 탈출 시도
    return path if os.path.isfile(path) else None


def parse_range(header, size):
    """단일 Range만 지원. None=전체, 'unsatisfiable'=416, (start,end)=206."""
    m = RANGE_RE.match(header or "")
    if not m:
        return None
    start_s, end_s = m.groups()
    if not start_s and not end_s:
        return None
    if not start_s:  # suffix: bytes=-N (마지막 N바이트)
        n = int(end_s)
        if n == 0 or size == 0:
            return "unsatisfiable"
        return (max(0, size - n), size - 1)
    start = int(start_s)
    if start >= size:
        return "unsatisfiable"
    end = min(int(end_s), size - 1) if end_s else size - 1
    if start > end:
        return "unsatisfiable"
    return (start, end)


CONTENT_TYPES = {
    ".mp4": "video/mp4",
    ".png": "image/png",
    ".json": "application/json",
}


class ClipHandler(BaseHTTPRequestHandler):
    root = "."  # serve() 에서 주입
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self._handle(head=False)

    def do_HEAD(self):
        self._handle(head=True)

    def _handle(self, head):
        url = urlparse(self.path)
        if url.path == "/api/events":
            self._send_events(url, head)
        elif url.path.startswith("/files/"):
            self._send_file(url.path[len("/files/"):], head)
        else:
            self.send_error(404)

    def _send_events(self, url, head):
        try:
            limit = max(1, int(parse_qs(url.query).get("limit", ["100"])[0]))
        except ValueError:
            limit = 100
        body = json.dumps(scan_events(self.root, limit)).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def _send_file(self, rel, head):
        path = resolve_file(self.root, rel)
        if path is None:
            self.send_error(404)
            return
        size = os.path.getsize(path)
        rng = parse_range(self.headers.get("Range"), size)
        if rng == "unsatisfiable":
            self.send_response(416)
            self.send_header("Content-Range", "bytes */%d" % size)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        start, end = rng if rng else (0, size - 1)
        length = max(0, end - start + 1)
        ext = os.path.splitext(path)[1].lower()
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", CONTENT_TYPES.get(ext, "application/octet-stream"))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if rng:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if head:
            return
        try:
            with open(path, "rb") as f:
                f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = f.read(min(CHUNK, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass  # 폰이 다운로드 중단 — 정상 상황


def serve(root, bind, port):
    ClipHandler.root = root
    httpd = ThreadingHTTPServer((bind, port), ClipHandler)
    print("clipserver: root=%s listening on %s:%d" % (root, bind, httpd.server_address[1]))
    httpd.serve_forever()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="TeslaSync 클립 서버")
    ap.add_argument("--root", default="/backingfiles/archive_share",
                    help="teslausb archive 루트 (기본: %(default)s)")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--bind", default="0.0.0.0")
    args = ap.parse_args()
    serve(args.root, args.bind, args.port)
