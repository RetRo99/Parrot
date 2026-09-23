#!/usr/bin/env python3
"""Create the first local Storyteller admin using its native setup form."""

from __future__ import annotations

import http.cookiejar
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from html.parser import HTMLParser
from pathlib import Path


BASE_URL = os.environ.get("STORYTELLER_URL", "http://localhost:8001").rstrip("/")
CONFIG_DIR = Path(
    os.environ.get(
        "STORYTELLER_CONFIG_DIR",
        Path.home() / "Library/Application Support/Storyteller",
    )
)
CREDENTIALS_FILE = CONFIG_DIR / "STORYTELLER_ADMIN_CREDENTIALS.txt"


class SetupFormParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.forms: list[tuple[str, dict[str, str]]] = []
        self.current_action: str | None = None
        self.current_hidden: dict[str, str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        if tag == "form":
            self.current_action = attributes.get("action") or "/init"
            self.current_hidden = {}
        elif (
            tag == "input"
            and self.current_hidden is not None
            and attributes.get("type", "").lower() == "hidden"
        ):
            name = attributes.get("name")
            if name:
                self.current_hidden[name] = attributes.get("value") or ""

    def handle_endtag(self, tag: str) -> None:
        if tag == "form" and self.current_hidden is not None:
            self.forms.append((self.current_action or "/init", self.current_hidden))
            self.current_action = None
            self.current_hidden = None


def read_credentials() -> dict[str, str]:
    if not CREDENTIALS_FILE.is_file():
        raise RuntimeError(f"Admin credentials file not found: {CREDENTIALS_FILE}")

    credentials: dict[str, str] = {}
    for line in CREDENTIALS_FILE.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition(":")
        if separator:
            credentials[key.strip().lower()] = value.strip()

    required = ("username", "email", "full name", "password")
    missing = [key for key in required if not credentials.get(key)]
    if missing:
        raise RuntimeError(f"Credentials file is missing: {', '.join(missing)}")
    return credentials


def multipart_body(fields: dict[str, str]) -> tuple[bytes, str]:
    boundary = f"----storyteller-{uuid.uuid4().hex}"
    chunks: list[bytes] = []
    for name, value in fields.items():
        safe_name = name.replace('"', "")
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{safe_name}"\r\n\r\n'.encode(),
                value.encode("utf-8"),
                b"\r\n",
            ]
        )
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def main() -> int:
    parsed_base = urllib.parse.urlparse(BASE_URL)
    if parsed_base.scheme != "http" or parsed_base.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise RuntimeError("Admin bootstrap is restricted to a local HTTP Storyteller URL")

    credentials = read_credentials()
    cookie_jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))

    setup_url = f"{BASE_URL}/init"
    request = urllib.request.Request(setup_url, headers={"Accept": "text/html"})
    with opener.open(request, timeout=15) as response:
        page = response.read().decode("utf-8", errors="replace")
        page_url = response.geturl()

    parser = SetupFormParser()
    parser.feed(page)
    selected_form = next(
        (
            (action, hidden)
            for action, hidden in parser.forms
            if any(name.startswith("$ACTION_ID_") for name in hidden)
        ),
        None,
    )

    if selected_form is not None:
        action, fields = selected_form
        action_url = urllib.parse.urljoin(page_url, action)
        fields.update(
            {
                "email": credentials["email"],
                "fullName": credentials["full name"],
                "username": credentials["username"],
                "password": credentials["password"],
            }
        )
        body, content_type = multipart_body(fields)
        request = urllib.request.Request(
            action_url,
            data=body,
            headers={
                "Accept": "text/html,application/xhtml+xml",
                "Content-Type": content_type,
                "Origin": BASE_URL,
                "Referer": page_url,
            },
        )
        with opener.open(request, timeout=30) as response:
            response.read()
    elif "/login" not in page_url and not re.search(r"name=[\"']\$ACTION_ID_", page):
        raise RuntimeError(
            "Storyteller did not expose its first-run setup form; refusing to alter an existing account"
        )

    # Confirm credentials through the documented local token endpoint without
    # printing or persisting the issued session token.
    token_request = urllib.request.Request(
        f"{BASE_URL}/api/v2/token",
        data=urllib.parse.urlencode(
            {
                "usernameOrEmail": credentials["username"],
                "password": credentials["password"],
            }
        ).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with opener.open(token_request, timeout=15) as response:
            token_response = json.loads(response.read())
    except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError) as error:
        raise RuntimeError("Admin account could not be verified by Storyteller") from error

    if not isinstance(token_response, dict) or not token_response.get("access_token"):
        raise RuntimeError("Storyteller did not return an access token for the local admin")

    print(f"Created and verified Storyteller admin '{credentials['username']}'.")
    print(f"Credentials are stored privately at: {CREDENTIALS_FILE}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, urllib.error.URLError) as error:
        print(f"Storyteller admin setup failed: {error}", file=sys.stderr)
        raise SystemExit(1)
