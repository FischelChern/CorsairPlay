#!/usr/bin/env python3
"""CorsairPlay activation, diagnostic upload and update-manifest API."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

CONFIG_PATH = Path("/etc/corsairplay/config.json")
DEFAULT_ACTIVATION_DAYS = 365
MAX_ACTIVATION_BYTES = 4 * 1024
MAX_REPORT_BYTES = 8 * 1024 * 1024
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]+$")


def load_config() -> dict[str, Any]:
    with CONFIG_PATH.open("r", encoding="utf-8") as source:
        config = json.load(source)
    data_dir = Path(config.get("data_dir", "/var/lib/corsairplay"))
    config["data_dir"] = str(data_dir)
    config["apk_dir"] = str(Path(config.get("apk_dir", str(data_dir / "apks"))))
    config["activation_days"] = int(config.get("activation_days", DEFAULT_ACTIVATION_DAYS))
    config.setdefault("upload_token", "")
    return config


def connect(config: dict[str, Any]) -> sqlite3.Connection:
    connection = sqlite3.connect(str(Path(config["data_dir"]) / "corsairplay.db"))
    connection.row_factory = sqlite3.Row
    return connection


def init_database(config: dict[str, Any]) -> None:
    data_dir = Path(config["data_dir"])
    (data_dir / "reports").mkdir(parents=True, exist_ok=True)
    Path(config["apk_dir"]).mkdir(parents=True, exist_ok=True)
    with connect(config) as connection:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS codes (
                code_hash TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                used_at INTEGER,
                device_id TEXT
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS activations (
                device_id TEXT PRIMARY KEY,
                code_hash TEXT NOT NULL,
                activated_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            )
            """
        )
        columns = {row[1] for row in connection.execute("PRAGMA table_info(activations)")}
        if "upload_token" not in columns:
            connection.execute("ALTER TABLE activations ADD COLUMN upload_token TEXT")


def code_hash(code: str) -> str:
    return hashlib.sha256(code.strip().encode("utf-8")).hexdigest()


def iso_utc(epoch_seconds: int) -> str:
    return datetime.fromtimestamp(epoch_seconds, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def read_limited(handler: BaseHTTPRequestHandler, limit: int) -> bytes:
    try:
        length = int(handler.headers.get("Content-Length", "0"))
    except ValueError:
        raise ValueError("invalid content length")
    if length < 0 or length > limit:
        raise ValueError("body too large")
    return handler.rfile.read(length) if length else b""


def send_json(handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class ApiHandler(BaseHTTPRequestHandler):
    server_version = "CorsairPlayAPI/1.0"
    config: dict[str, Any]

    def log_message(self, fmt: str, *args: Any) -> None:
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        sys.stderr.write(f"{stamp} {self.address_string()} {fmt % args}\n")

    def authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return False
        candidate = header[7:].strip()
        if not candidate:
            return False
        global_token = self.config.get("upload_token", "")
        if global_token and hmac.compare_digest(candidate, global_token):
            return True
        with connect(self.config) as connection:
            row = connection.execute(
                """
                SELECT 1 FROM activations
                WHERE upload_token = ? AND expires_at > ?
                """,
                (candidate, int(time.time())),
            ).fetchone()
        return row is not None

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        try:
            if parsed.path == "/api/health":
                send_json(self, 200, {"ok": True, "time": iso_utc(int(time.time()))})
                return
            if parsed.path == "/api/activate":
                self.get_activation(parsed.query)
                return
            if parsed.path == "/api/updates/manifest.json":
                self.serve_update_file(Path(self.config["apk_dir"]) / "manifest.json", "application/json")
                return
            if parsed.path.startswith("/api/updates/"):
                self.serve_update_file_from_name(parsed.path[len("/api/updates/") :])
                return
            send_json(self, 404, {"error": "not_found"})
        except Exception as failure:
            self.server_error(failure)

    def do_HEAD(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        try:
            if parsed.path.startswith("/api/updates/"):
                name = parsed.path[len("/api/updates/") :]
                candidate = Path(self.config["apk_dir"]) / name if SAFE_NAME.fullmatch(name) else None
                if candidate is not None and candidate.is_file():
                    self.send_response(200)
                    self.send_header("Content-Type", self.content_type(candidate))
                    self.send_header("Content-Length", str(candidate.stat().st_size))
                    self.end_headers()
                    return
            self.send_response(404)
            self.end_headers()
        except Exception as failure:
            self.server_error(failure)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        try:
            if parsed.path == "/api/activate":
                self.post_activation()
                return
            if parsed.path == "/api/diagnostics":
                self.post_diagnostics()
                return
            send_json(self, 404, {"error": "not_found"})
        except Exception as failure:
            self.server_error(failure)

    def get_activation(self, query: str) -> None:
        device_id = parse_qs(query).get("device_id", [""])[0].strip()[:128]
        if not device_id:
            send_json(self, 400, {"error": "device_id_required"})
            return
        now = int(time.time())
        with connect(self.config) as connection:
            row = connection.execute(
                "SELECT device_id, expires_at, last_seen FROM activations WHERE device_id = ?",
                (device_id,),
            ).fetchone()
        active = row is not None and int(row["expires_at"]) > now
        if active:
            with connect(self.config) as connection:
                connection.execute(
                    "UPDATE activations SET last_seen = ? WHERE device_id = ?", (now, device_id)
                )
        send_json(
            self,
            200,
            {
                "active": active,
                "device_id": device_id,
                "expires_at": iso_utc(int(row["expires_at"])) if row is not None else None,
            },
        )

    def post_activation(self) -> None:
        raw = read_limited(self, MAX_ACTIVATION_BYTES)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            send_json(self, 400, {"error": "invalid_json"})
            return
        if not isinstance(payload, dict):
            send_json(self, 400, {"error": "invalid_json"})
            return
        device_id = str(payload.get("device_id", "")).strip()[:128]
        activation_code = str(payload.get("code", "")).strip()[:128]
        if not device_id or not activation_code:
            send_json(self, 400, {"error": "device_id_and_code_required"})
            return

        digest = code_hash(activation_code)
        now = int(time.time())
        expires_at = now + int(self.config["activation_days"]) * 86400
        upload_token = secrets.token_urlsafe(32)
        with connect(self.config) as connection:
            row = connection.execute(
                "SELECT device_id FROM codes WHERE code_hash = ?", (digest,)
            ).fetchone()
            if row is None:
                send_json(self, 403, {"active": False, "error": "invalid_code"})
                return
            bound_device = row["device_id"]
            if bound_device and bound_device != device_id:
                send_json(self, 403, {"active": False, "error": "code_already_bound"})
                return
            connection.execute(
                """
                INSERT INTO activations(device_id, code_hash, activated_at, expires_at, last_seen, upload_token)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    code_hash = excluded.code_hash,
                    activated_at = excluded.activated_at,
                    expires_at = excluded.expires_at,
                    last_seen = excluded.last_seen,
                    upload_token = excluded.upload_token
                """,
                (device_id, digest, now, expires_at, now, upload_token),
            )
            connection.execute(
                "UPDATE codes SET used_at = ?, device_id = ? WHERE code_hash = ?",
                (now, device_id, digest),
            )
        send_json(
            self,
            200,
            {
                "active": True,
                "device_id": device_id,
                "expires_at": iso_utc(expires_at),
                "upload_token": upload_token,
            },
        )

    def post_diagnostics(self) -> None:
        if not self.authorized():
            send_json(self, 401, {"error": "unauthorized"})
            return
        content_type = self.headers.get("Content-Type", "")
        if not content_type.lower().startswith("text/plain"):
            send_json(self, 415, {"error": "text_plain_required"})
            return
        report = read_limited(self, MAX_REPORT_BYTES)
        if not report:
            send_json(self, 400, {"error": "empty_report"})
            return
        now = datetime.now(timezone.utc)
        report_id = now.strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
        day_dir = Path(self.config["data_dir"]) / "reports" / now.strftime("%Y%m%d")
        day_dir.mkdir(parents=True, exist_ok=True)
        destination = day_dir / f"{report_id}.txt"
        temporary = destination.with_suffix(".tmp")
        temporary.write_bytes(report)
        os.replace(temporary, destination)
        send_json(self, 200, {"ok": True, "id": report_id, "bytes": len(report)})

    @staticmethod
    def content_type(path: Path) -> str:
        if path.suffix == ".apk":
            return "application/vnd.android.package-archive"
        if path.suffix == ".txt":
            return "text/plain; charset=utf-8"
        return "application/octet-stream"

    def serve_update_file_from_name(self, name: str) -> None:
        if not SAFE_NAME.fullmatch(name) or ".." in name:
            send_json(self, 400, {"error": "invalid_name"})
            return
        self.serve_update_file(Path(self.config["apk_dir"]) / name)

    def serve_update_file(self, path: Path, override_type: str | None = None) -> None:
        try:
            resolved = path.resolve()
            root = Path(self.config["apk_dir"]).resolve()
            if root not in resolved.parents and resolved != root:
                send_json(self, 400, {"error": "invalid_path"})
                return
            if not resolved.is_file():
                send_json(self, 404, {"error": "not_found"})
                return
            self.send_response(200)
            self.send_header("Content-Type", override_type or self.content_type(resolved))
            self.send_header("Content-Length", str(resolved.stat().st_size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with resolved.open("rb") as source:
                while chunk := source.read(64 * 1024):
                    self.wfile.write(chunk)
        except OSError as failure:
            self.server_error(failure)

    def server_error(self, failure: Exception) -> None:
        sys.stderr.write(f"API error: {failure.__class__.__name__}: {failure}\n")
        try:
            send_json(self, 500, {"error": "internal_error"})
        except OSError:
            pass


def main() -> None:
    config = load_config()
    init_database(config)
    host = config.get("bind", "127.0.0.1")
    port = int(config.get("port", 8765))
    handler = type("ConfiguredApiHandler", (ApiHandler,), {"config": config})
    server = ThreadingHTTPServer((host, port), handler)
    sys.stderr.write(f"CorsairPlay API listening on {host}:{port}\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
