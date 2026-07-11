#!/usr/bin/env python3
"""clipserver.py 테스트 — 가짜 TeslaCam 아카이브 트리 위에서 실제 HTTP로 검증.

실행:  python3 pi/test_clipserver.py
"""
import http.client
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clipserver  # noqa: E402

FRONT = b"FRONT-VIDEO-BYTES-0123456789"  # 28 bytes
BACK = b"BACK"


def build_tree(root):
    saved = os.path.join(root, "SavedClips", "2026-06-24_16-17-43")
    os.makedirs(saved)
    with open(os.path.join(saved, "2026-06-24_16-16-01-front.mp4"), "wb") as f:
        f.write(FRONT)
    with open(os.path.join(saved, "2026-06-24_16-16-01-back.mp4"), "wb") as f:
        f.write(BACK)
    with open(os.path.join(saved, "event.json"), "w", encoding="utf-8") as f:
        json.dump({"timestamp": "2026-06-24T16:17:43", "city": "", "street": "테스트로",
                   "reason": "user_interaction_dashcam_multifunction_selected",
                   "est_lat": "0", "est_lon": "0", "camera": "0"}, f)
    with open(os.path.join(saved, "thumb.png"), "wb") as f:
        f.write(b"PNG")

    # event.json이 깨진 Sentry 이벤트 — 목록엔 나오되 폴더명으로 폴백해야 함
    sentry = os.path.join(root, "SentryClips", "2026-07-01_09-00-00")
    os.makedirs(sentry)
    with open(os.path.join(sentry, "2026-07-01_08-59-00-left_repeater.mp4"), "wb") as f:
        f.write(b"L" * 10)
    with open(os.path.join(sentry, "event.json"), "w") as f:
        f.write("{broken json")

    # 제외 대상들: RecentClips(평평한 구조), mp4 없는 이벤트, 잡파일
    recent = os.path.join(root, "RecentClips")
    os.makedirs(recent)
    with open(os.path.join(recent, "2026-07-02_10-00-00-front.mp4"), "wb") as f:
        f.write(b"R")
    os.makedirs(os.path.join(root, "SavedClips", "2026-01-01_00-00-00"))  # 빈 이벤트
    with open(os.path.join(root, "SavedClips", "stray.txt"), "w") as f:
        f.write("x")
    with open(os.path.join(root, "secret.txt"), "w") as f:
        f.write("MUST-NOT-LEAK")


class ClipServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = tempfile.mkdtemp(prefix="clipserver-test-")
        build_tree(cls.root)
        clipserver.ClipHandler.root = cls.root
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), clipserver.ClipHandler)
        cls.port = cls.httpd.server_address[1]
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        shutil.rmtree(cls.root)

    def request(self, path, headers=None, method="GET"):
        """urllib은 ../를 정규화해버려서 raw HTTPConnection 사용."""
        conn = http.client.HTTPConnection("127.0.0.1", self.port)
        conn.request(method, path, headers=headers or {})
        resp = conn.getresponse()
        body = resp.read()
        conn.close()
        return resp, body

    # ── /healthz ─────────────────────────────────────────────────

    def test_healthz(self):
        resp, body = self.request("/healthz")
        self.assertEqual(resp.status, 200)
        data = json.loads(body)
        self.assertTrue(data["ok"])
        self.assertEqual(data["root"], self.root)
        self.assertEqual(data["event_dirs"], {"SavedClips": True, "SentryClips": True})

    def test_healthz_head(self):
        resp, body = self.request("/healthz", method="HEAD")
        self.assertEqual(resp.status, 200)
        self.assertEqual(body, b"")
        self.assertEqual(resp.getheader("Content-Type"), "application/json; charset=utf-8")

    # ── /api/events ──────────────────────────────────────────────

    def test_events_listing_and_order(self):
        resp, body = self.request("/api/events")
        self.assertEqual(resp.status, 200)
        events = json.loads(body)
        # Saved 1 + Sentry 1. RecentClips·빈 이벤트·잡파일은 제외
        self.assertEqual([e["name"] for e in events],
                         ["2026-07-01_09-00-00", "2026-06-24_16-17-43"])  # 최신순
        self.assertEqual([e["type"] for e in events], ["SentryClips", "SavedClips"])

    def test_event_metadata_and_clips(self):
        _, body = self.request("/api/events")
        saved = [e for e in json.loads(body) if e["type"] == "SavedClips"][0]
        self.assertEqual(saved["timestamp"], "2026-06-24T16:17:43")
        self.assertEqual(saved["street"], "테스트로")
        self.assertTrue(saved["thumb"])
        # 클립은 mp4만 — event.json/thumb.png가 섞이면 안 됨 (과거 SyncService 버그 재발 방지)
        self.assertEqual({c["file"] for c in saved["clips"]},
                         {"2026-06-24_16-16-01-front.mp4", "2026-06-24_16-16-01-back.mp4"})
        front = [c for c in saved["clips"] if c["camera"] == "front"][0]
        self.assertEqual(front["size"], len(FRONT))

    def test_broken_event_json_falls_back(self):
        _, body = self.request("/api/events")
        sentry = [e for e in json.loads(body) if e["type"] == "SentryClips"][0]
        self.assertEqual(sentry["timestamp"], "2026-07-01_09-00-00")  # 폴더명 폴백
        self.assertEqual(sentry["clips"][0]["camera"], "left_repeater")

    def test_events_limit(self):
        _, body = self.request("/api/events?limit=1")
        self.assertEqual(len(json.loads(body)), 1)

    # ── /files ───────────────────────────────────────────────────

    FRONT_PATH = "/files/SavedClips/2026-06-24_16-17-43/2026-06-24_16-16-01-front.mp4"

    def test_full_download(self):
        resp, body = self.request(self.FRONT_PATH)
        self.assertEqual(resp.status, 200)
        self.assertEqual(body, FRONT)
        self.assertEqual(resp.getheader("Content-Type"), "video/mp4")
        self.assertEqual(resp.getheader("Accept-Ranges"), "bytes")

    def test_range_partial(self):
        resp, body = self.request(self.FRONT_PATH, {"Range": "bytes=2-5"})
        self.assertEqual(resp.status, 206)
        self.assertEqual(body, FRONT[2:6])
        self.assertEqual(resp.getheader("Content-Range"),
                         "bytes 2-5/%d" % len(FRONT))

    def test_range_open_ended_resume(self):
        resp, body = self.request(self.FRONT_PATH, {"Range": "bytes=10-"})
        self.assertEqual(resp.status, 206)
        self.assertEqual(body, FRONT[10:])

    def test_range_suffix(self):
        resp, body = self.request(self.FRONT_PATH, {"Range": "bytes=-4"})
        self.assertEqual(resp.status, 206)
        self.assertEqual(body, FRONT[-4:])

    def test_range_unsatisfiable(self):
        resp, _ = self.request(self.FRONT_PATH, {"Range": "bytes=9999-"})
        self.assertEqual(resp.status, 416)

    def test_head(self):
        resp, body = self.request(self.FRONT_PATH, method="HEAD")
        self.assertEqual(resp.status, 200)
        self.assertEqual(body, b"")
        self.assertEqual(resp.getheader("Content-Length"), str(len(FRONT)))

    def test_thumb_served(self):
        resp, _ = self.request("/files/SavedClips/2026-06-24_16-17-43/thumb.png")
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.getheader("Content-Type"), "image/png")

    # ── 보안/경계 ─────────────────────────────────────────────────

    def test_traversal_blocked(self):
        resp, body = self.request(
            "/files/SavedClips/../../secret.txt".replace("..", "%2e%2e"))
        self.assertEqual(resp.status, 404)
        resp, body = self.request("/files/SavedClips/../secret.txt")
        self.assertNotEqual(resp.status, 200)
        self.assertNotIn(b"MUST-NOT-LEAK", body)

    def test_recentclips_not_served(self):
        resp, _ = self.request("/files/RecentClips/2026-07-02_10-00-00-front.mp4")
        self.assertEqual(resp.status, 404)

    def test_unknown_route(self):
        resp, _ = self.request("/api/nope")
        self.assertEqual(resp.status, 404)

    # ── TeslaCam/ 하위 배치 자동 인식 ─────────────────────────────

    def test_nested_teslacam_root(self):
        nested = tempfile.mkdtemp(prefix="clipserver-nested-")
        try:
            ev = os.path.join(nested, "TeslaCam", "SavedClips", "2026-05-05_05-05-05")
            os.makedirs(ev)
            with open(os.path.join(ev, "2026-05-05_05-04-00-front.mp4"), "wb") as f:
                f.write(b"N")
            events = clipserver.scan_events(nested)
            self.assertEqual(len(events), 1)
            self.assertEqual(events[0]["name"], "2026-05-05_05-05-05")
        finally:
            shutil.rmtree(nested)


if __name__ == "__main__":
    unittest.main(verbosity=2)
