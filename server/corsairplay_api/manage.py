#!/usr/bin/env python3
"""Administration helper for CorsairPlay activation codes."""

from __future__ import annotations

import argparse
import secrets
import sys
import time

from server import code_hash, connect, init_database, iso_utc, load_config


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate = commands.add_parser("gen-code", help="generate activation codes")
    generate.add_argument("--count", type=int, default=1)
    commands.add_parser("list-codes", help="show activation code state")
    commands.add_parser("list-activations", help="show device activations")
    revoke = commands.add_parser("revoke", help="revoke a device activation")
    revoke.add_argument("device_id")
    args = parser.parse_args()

    config = load_config()
    init_database(config)
    now = int(time.time())

    with connect(config) as connection:
        if args.command == "gen-code":
            if args.count < 1 or args.count > 100:
                parser.error("--count must be between 1 and 100")
            for _ in range(args.count):
                code = secrets.token_urlsafe(18)
                connection.execute(
                    "INSERT INTO codes(code_hash, created_at) VALUES (?, ?)", (code_hash(code), now)
                )
                print(code)
            return

        if args.command == "list-codes":
            rows = connection.execute(
                "SELECT code_hash, created_at, used_at, device_id FROM codes ORDER BY created_at DESC"
            ).fetchall()
            for row in rows:
                used = iso_utc(int(row["used_at"])) if row["used_at"] else "unused"
                print(f"{row['code_hash'][:12]} created={iso_utc(int(row['created_at']))} used={used} device={row['device_id'] or '-'}")
            return

        if args.command == "list-activations":
            rows = connection.execute(
                "SELECT device_id, expires_at, last_seen FROM activations ORDER BY expires_at DESC"
            ).fetchall()
            for row in rows:
                active = "active" if int(row["expires_at"]) > now else "expired"
                print(
                    f"{row['device_id']} {active} expires={iso_utc(int(row['expires_at']))} "
                    f"last_seen={iso_utc(int(row['last_seen']))}"
                )
            return

        if args.command == "revoke":
            cursor = connection.execute("DELETE FROM activations WHERE device_id = ?", (args.device_id,))
            print(f"removed {cursor.rowcount} activation(s)")


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        sys.stderr.write(f"{failure.__class__.__name__}: {failure}\n")
        raise SystemExit(1)
