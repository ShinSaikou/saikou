#!/usr/bin/env python3
"""The host-side fixture server for sync: a WebDAV subset, Google's OAuth endpoints and Drive v3's
appDataFolder, faked on one port. Unit D of design/plans/phase3-start.md.

It follows update_fixture_server.py: loopback only, on --port (18795 when the gate runs it; the
emulators reach it as 10.0.2.2:18795, and 18794 stays the update fixture's), "ready <port>" on stdout
once it listens, exit 3 when the port is taken, one JSON line per request in --log, and a stop on
SIGTERM or SIGINT. Whatever the clients store lives in one fresh directory per run, made under
--state-root (the system temporary directory by default), named in the log's start line and removed
when the server stops. A stop closes idle connections and waits for every request being answered,
so each answered request has its line and its effect before the state goes and the log closes. The
wait lasts STOP_GRACE_S at most: a request still unanswered then (its client stalled in the middle
of its body, or stopped reading the answer) has its connection closed in both directions, and is
logged with "aborted": true, so a stalled client never holds the stop, and the orchestrator's plain
SIGTERM and wait, which has no kill after it, always returns.

Paths, each shaped like the service it fakes, so a client only swaps scheme and host:

    /dav/...                    WebDAV, Basic authentication against --dav-user and --dav-password.
                                PROPFIND (Depth 0 or 1; no Depth, or infinity, is 403), GET, HEAD,
                                PUT (409 without the parent collection, 201 new, 204 replaced), DELETE,
                                MKCOL, OPTIONS. Every resource has a strong ETag, collections too, and
                                PUT and DELETE honour If-Match and If-None-Match. A chunked request
                                body is accepted, as OkHttp sends one for a body of unknown length.
    /o/oauth2/v2/auth           the authorization endpoint. A GET with client_id, a loopback
                                redirect_uri, response_type=code, scope, an S256 code_challenge and a
                                state answers 302 to redirect_uri with state, code and scope: it is
                                the consent screen already approved, so a test calls it with no browser.
    /token                      the token endpoint, form encoded. It requires client_secret on the code
                                exchange and on every refresh, as Google does for a Desktop client
                                (a missing one is 400 invalid_request, a wrong one 401 invalid_client),
                                checks the PKCE verifier, and never rotates the refresh token. Access
                                tokens last --access-token-lifetime seconds unless the modes say
                                otherwise; an unknown, revoked or expired refresh token is 400
                                invalid_grant.
    /revoke                     revokes the grant a refresh or access token belongs to (200), or 400
                                invalid_token.
    /drive/v3/files             files.list (spaces=appDataFolder only, else 403; pageSize 1 to 1000,
                                all files when absent; pageToken; q with 'X' in parents, name = or !=,
                                name contains (a prefix match, as Google documents), mimeType = or !=,
                                trashed = true|false, joined by and; orderBy on createdTime,
                                modifiedTime and name, each with an optional desc), and a
                                metadata-only files.create with POST (a folder, or an empty file).
    /drive/v3/files/<id>        files.get (alt=media for the bytes) and files.delete (204).
    /drive/v3/about             about.get; the fields parameter is required, as Google requires it.
    /upload/drive/v3/files      files.create with uploadType=multipart (200 and the file) or
                                uploadType=resumable (200 with a Location naming the session). A PUT
                                to the session carries the whole file, a chunk with Content-Range
                                "bytes S-E/T" or "bytes S-E/*", or a status query "bytes */T". A chunk
                                that does not finish the file keeps only the bytes up to a multiple
                                of 256 KiB and answers 308 with Range "bytes=0-N" (no Range when none
                                is kept, and no Location), so the client has to resume from Range.
                                A finished session answers 200 and the file, then or when asked
                                again; an unknown session is 404, as an expired one is at Google.

Drive's JSON follows Google's reference: files are drive#file with size and version as strings,
times in RFC 3339 with milliseconds and UTC, parents naming the appDataFolder's own id (never the
alias), and the default fields kind, id, name and mimeType unless a fields parameter selects others
(a,b(c,d) and a/b; an unknown field is 400). Errors are {"error": {"errors": [...], "code", "message"}}.
A Drive call needs "Authorization: Bearer <access token>" with the drive.appdata scope: none, an
unknown, expired or revoked one is 401 with WWW-Authenticate, another scope 403. A session PUT
without an Authorization header is served, as the session address is the credential (Google's own
upload guide sends none); a session PUT that carries one has it checked like any Drive call, so an
expired or revoked token is 401 there too, stores nothing, and counts toward early401.

Modes, switched only by the host's tests and the gate's orchestrator through the control path; the
app never calls it. Every control request is logged like any other.

    GET  /_fixture/state                  the modes and how much is stored
    POST /_fixture/modes                  a JSON object with any of the keys below; answers the state
    POST /_fixture/expire-access-tokens   every access token issued so far expires now
    POST /_fixture/reset                  default modes and nothing stored, as a fresh start

    lie                    true: a WebDAV PUT and every Drive files.create answer success, with the
                           ETag or file they would have produced, and store nothing
    hide                   a WebDAV path under /dav/ (no leading slash) or a Drive file name that
                           PROPFIND and files.list leave out; the file itself still answers GET
    revoked                true: every grant reads as revoked, so a refresh is invalid_grant and a
                           Drive call 401, until it is set false again
    refreshFailures        a list of "drop" (the connection is closed with no answer) and "503",
                           one taken per refresh_token grant before anything is checked
    early401               how many Drive calls with a valid, unexpired token are answered 401
    accessTokenLifetime    the expires_in of access tokens issued from now on (3599 by default)
    refreshTokenExpiresIn  null, or the lifetime in seconds of refresh tokens issued from now on;
                           token responses then carry refresh_token_expires_in, and a refresh token
                           past it is invalid_grant
    drivePageLimit         null, or the most files one files.list page holds whatever pageSize asks
    authorizeDelay         null, or how many seconds the authorization endpoint holds its answer, the
                           time an owner takes to approve in the browser (unit O's spike); the code
                           is issued when the wait ends, and a stop ends the wait with a 503

Any other path, with any method, is 404.

Latency (phase 5 unit SC3): --latency-ms N holds every request but a control request N milliseconds
after its body is read and before it is handled, so before any lock is taken: requests on parallel
connections overlap their waits, as a real server's round trips do, while each request still takes
the fixture's lock alone. The default 0 holds nothing. A stop ends a wait at once. Each held request
is logged with "latency_ms".

Every request is logged as one JSON line, written once its answer is, so lines are in the order the
answers finished and t0 gives the order of arrival. Tokens, codes and secrets are never logged, so the
path is logged without its query:

    {"t0": <server monotonic seconds at receipt>, "t1": <after the answer was written>,
     "method": "<method>", "path": "<path>", "kind": "<kind>", "status": <status, or null when the
     connection was dropped>, "aborted": <true when the client went away first>,
     "injected": <"drop", "503", "early401", "lie", "hide", "delay", or null>, ...}

Kinds: dav.propfind, dav.get, dav.head, dav.put, dav.delete, dav.mkcol, dav.options, dav.other;
oauth.authorize; oauth.token, with "grant" (the grant_type) and "client_secret" ("present",
"missing" or "wrong"); oauth.revoke; drive.files.list, drive.files.create, drive.files.get,
drive.files.delete, drive.about.get, drive.upload.chunk; control; unknown. The first line is
{"event": "start", ...} with the port and the state directory, the last {"event": "stop", ...}.

Usage: sync_fixture_server.py --log <file> [--port N] [--bind ADDRESS] [--state-root DIR]
           [--dav-user U] [--dav-password P] [--client-id ID] [--client-secret S]
           [--account-email E] [--access-token-lifetime SECONDS] [--latency-ms N]
Exit codes: 0 stopped; 2 usage; 3 the port could not be bound.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import json
import re
import secrets
import shutil
import signal
import socket
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from email.message import Message
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, quote, unquote, urlencode, urlsplit
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))
# The frozen update fixture's log writer (R66), reused unchanged: whole lines, under a lock.
from update_fixture_server import RequestLog  # noqa: E402

SERVER_PORT = 18795
DEFAULT_DAV_USER = "saikou"
DEFAULT_DAV_PASSWORD = "fixture-dav-password"
DEFAULT_CLIENT_ID = "fixture-desktop-client.apps.googleusercontent.com"
DEFAULT_CLIENT_SECRET = "fixture-desktop-client-secret"
DEFAULT_ACCOUNT_EMAIL = "owner@fixture.test"
ACCESS_TOKEN_LIFETIME_S = 3599
CODE_LIFETIME_S = 600
DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
FOLDER_MIME = "application/vnd.google-apps.folder"
RESUMABLE_CHUNK = 256 * 1024
# How long a stop waits for the requests being answered before it closes their connections.
STOP_GRACE_S = 5.0
MAX_PAGE_SIZE = 1000

DAV_PREFIX = "/dav/"
AUTH_PATH = "/o/oauth2/v2/auth"
TOKEN_PATH = "/token"
REVOKE_PATH = "/revoke"
DRIVE_FILES_PATH = "/drive/v3/files"
DRIVE_ABOUT_PATH = "/drive/v3/about"
UPLOAD_PATH = "/upload/drive/v3/files"
CONTROL_PREFIX = "/_fixture/"
REFRESH_FAILURES = ("drop", "503")
DAV_METHODS = "OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL"
PKCE_CHALLENGE = re.compile(r"^[A-Za-z0-9._~-]{43,128}$")


# -- Requests, replies and the pieces of Google's formats every service shares ----------------------

@dataclass
class Request:
    method: str
    path: str
    query: dict[str, str]
    headers: Message
    body: bytes
    host: str
    # What the answering service adds to the request's log line.
    note: dict = field(default_factory=dict)

    def header(self, name: str) -> str:
        return self.headers.get(name, "") or ""


@dataclass
class Reply:
    status: int
    body: bytes = b""
    content_type: str | None = None
    headers: dict[str, str] = field(default_factory=dict)
    # Close the connection without writing a byte: the dropped connection of the refresh sequence.
    drop: bool = False
    reason: str | None = None


def json_reply(status: int, value, headers: dict[str, str] | None = None) -> Reply:
    return Reply(status, json.dumps(value).encode(), "application/json; charset=UTF-8", headers or {})


def text_reply(status: int, text: str, headers: dict[str, str] | None = None) -> Reply:
    return Reply(status, text.encode(), "text/plain; charset=utf-8", headers or {})


def not_found(req: Request) -> Reply:
    req.note.setdefault("kind", "unknown")
    return text_reply(404, "no such path on the sync fixture\n")


def oauth_error(status: int, error: str, description: str) -> Reply:
    return json_reply(status, {"error": error, "error_description": description})


def drive_error(code: int, reason: str, message: str, location: str | None = None,
                location_type: str | None = None, headers: dict[str, str] | None = None) -> Reply:
    item = {"domain": "global", "reason": reason, "message": message}
    if location is not None:
        item["location"] = location
        item["locationType"] = location_type or "parameter"
    return json_reply(code, {"error": {"errors": [item], "code": code, "message": message}}, headers)


def unauthorized() -> Reply:
    return drive_error(401, "authError", "Invalid Credentials", "Authorization", "header",
                       {"WWW-Authenticate": 'Bearer realm="https://accounts.google.com/", error="invalid_token"'})


def rfc3339(seconds: float) -> str:
    moment = datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc)
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def http_date(seconds: float) -> str:
    return formatdate(seconds, usegmt=True)


def read_body(rfile, headers: Message) -> bytes:
    """The request body, from Content-Length or chunked transfer coding. ValueError when malformed."""
    if "chunked" in (headers.get("Transfer-Encoding") or "").lower():
        parts = []
        while True:
            line = rfile.readline(65537)
            if not line:
                raise ValueError("the chunked body ended early")
            size = int(line.split(b";", 1)[0].strip(), 16)
            if size == 0:
                while rfile.readline(65537) not in (b"\r\n", b"\n", b""):
                    pass  # trailers
                return b"".join(parts)
            chunk = rfile.read(size)
            if len(chunk) != size:
                raise ValueError("the chunked body ended early")
            parts.append(chunk)
            rfile.readline(3)
    length = int(headers.get("Content-Length") or 0)
    if length < 0:
        raise ValueError("a negative Content-Length")
    body = rfile.read(length) if length else b""
    if len(body) != length:
        raise ValueError("the body ended early")
    return body


# Drive's fields parameter: a comma list of names, a/b for a nested name, a(b,c) for a sub-selection.
FIELD_NAME = re.compile(r"\s*([A-Za-z*][A-Za-z0-9_]*(?:/[A-Za-z*][A-Za-z0-9_]*)*)\s*")


def _merge_spec(target: dict, name: str, sub: dict | None) -> None:
    if name in target and (target[name] is None or sub is None):
        target[name] = None
    elif name in target:
        for key, value in sub.items():
            _merge_spec(target[name], key, value)
    else:
        target[name] = sub


def parse_fields(text: str) -> dict:
    """{name: None for the whole value, or a nested selection}. ValueError when it does not parse."""
    pos = 0

    def parse_list(closing: str | None) -> dict:
        nonlocal pos
        out: dict = {}
        while True:
            match = FIELD_NAME.match(text, pos)
            if not match:
                raise ValueError(text[pos:] or text)
            names = match.group(1).split("/")
            pos = match.end()
            sub = None
            if pos < len(text) and text[pos] == "(":
                pos += 1
                sub = parse_list(")")
                pos += 1
                while pos < len(text) and text[pos].isspace():
                    pos += 1
            for name in reversed(names[1:]):
                sub = {name: sub}
            _merge_spec(out, names[0], sub)
            if pos == len(text):
                if closing is not None:
                    raise ValueError(text)
                return out
            if text[pos] == ",":
                pos += 1
                continue
            if closing is not None and text[pos] == closing:
                return out
            raise ValueError(text[pos:])

    return parse_list(None)


def invalid_field(spec: dict, schema: dict, prefix: str = "") -> str | None:
    """The first selected name the schema does not have, or None."""
    for name, sub in spec.items():
        if name == "*":
            if sub is not None:
                return prefix + name
            continue
        if name not in schema or (sub is not None and schema[name] is None):
            return prefix + name
        if sub is not None:
            bad = invalid_field(sub, schema[name], f"{prefix}{name}/")
            if bad:
                return bad
    return None


def project(value, spec: dict | None):
    if spec is None or "*" in spec:
        return value
    if isinstance(value, list):
        return [project(item, spec) for item in value]
    return {name: project(value[name], sub) for name, sub in spec.items() if name in value}


FILE_SCHEMA: dict = {name: None for name in (
    "kind", "id", "name", "mimeType", "parents", "spaces", "trashed", "createdTime", "modifiedTime",
    "version", "size", "md5Checksum", "sha256Checksum")}
LIST_SCHEMA: dict = {"kind": None, "nextPageToken": None, "incompleteSearch": None, "files": FILE_SCHEMA}
ABOUT_SCHEMA: dict = {
    "kind": None,
    "user": {name: None for name in ("kind", "displayName", "me", "permissionId", "emailAddress")},
    "storageQuota": {name: None for name in ("limit", "usage", "usageInDrive", "usageInDriveTrash")},
}
DEFAULT_FILE_SPEC: dict = {"kind": None, "id": None, "name": None, "mimeType": None}
DEFAULT_LIST_SPEC: dict = {"kind": None, "nextPageToken": None, "incompleteSearch": None, "files": DEFAULT_FILE_SPEC}


def select_fields(req: Request, schema: dict, default: dict | None, required: bool = False):
    """(spec, None) for the request's fields parameter, or (None, the 400 Google answers)."""
    text = req.query.get("fields")
    if text is None or text == "":
        if required:
            return None, drive_error(400, "required", "The 'fields' parameter is required for this operation.",
                                     "fields")
        return default, None
    try:
        spec = parse_fields(text)
    except ValueError:
        return None, drive_error(400, "invalidParameter", f"Invalid field selection {text}", "fields")
    bad = invalid_field(spec, schema)
    if bad:
        return None, drive_error(400, "invalidParameter", f"Invalid field selection {bad}", "fields")
    return spec, None


# -- Modes -------------------------------------------------------------------------------------------

@dataclass
class Modes:
    lie: bool = False
    hide: str | None = None
    revoked: bool = False
    refresh_failures: list[str] = field(default_factory=list)
    early_401: int = 0
    access_token_lifetime: int = ACCESS_TOKEN_LIFETIME_S
    refresh_token_expires_in: int | None = None
    drive_page_limit: int | None = None
    authorize_delay: int | None = None

    KEYS = {
        "lie": "lie", "hide": "hide", "revoked": "revoked", "refreshFailures": "refresh_failures",
        "early401": "early_401", "accessTokenLifetime": "access_token_lifetime",
        "refreshTokenExpiresIn": "refresh_token_expires_in", "drivePageLimit": "drive_page_limit",
        "authorizeDelay": "authorize_delay",
    }

    def as_json(self) -> dict:
        return {key: getattr(self, attribute) for key, attribute in self.KEYS.items()}

    def update(self, values) -> str | None:
        """Applies a control request's object, all of it or none. The reason it was refused, or None."""
        if not isinstance(values, dict):
            return "the body is not a JSON object"
        checked = {}
        for key, value in values.items():
            if key not in self.KEYS:
                return f"no mode {key}"
            if key in ("lie", "revoked"):
                ok = isinstance(value, bool)
            elif key == "hide":
                ok = value is None or (isinstance(value, str) and value != "")
            elif key == "refreshFailures":
                ok = isinstance(value, list) and all(item in REFRESH_FAILURES for item in value)
                value = list(value) if ok else value
            elif key == "early401":
                ok = type(value) is int and value >= 0
            elif key == "accessTokenLifetime":
                ok = type(value) is int and value >= 1
            else:
                ok = value is None or (type(value) is int and value >= 1)
            if not ok:
                return f"{key} cannot be {json.dumps(value)}"
            checked[self.KEYS[key]] = value
        for attribute, value in checked.items():
            setattr(self, attribute, value)
        return None


# -- WebDAV ------------------------------------------------------------------------------------------

@dataclass
class DavEntry:
    collection: bool
    etag: str
    created: float
    modified: float
    size: int = 0
    content_type: str = "application/octet-stream"


def content_etag(data: bytes) -> str:
    return '"' + hashlib.sha256(data).hexdigest()[:32] + '"'


def dav_rel(path: str) -> str | None:
    """The resource's path below /dav/, "" for the root, or None when a segment is empty, . or .."""
    rel = unquote(path[len(DAV_PREFIX) - 1:]).strip("/")
    parts = rel.split("/") if rel else []
    if any(part in ("", ".", "..") for part in parts):
        return None
    return "/".join(parts)


def parent_of(rel: str) -> str:
    return rel.rsplit("/", 1)[0] if "/" in rel else ""


def etag_matches(header: str, etag: str | None) -> bool:
    values = [value.strip() for value in header.split(",")]
    return etag is not None and ("*" in values or etag in values or f"W/{etag}" in values)


class Dav:
    """The WebDAV resources: their properties here, their bytes under <state>/dav."""

    def __init__(self, fixture: Fixture):
        self.fixture = fixture
        self.root = fixture.state_dir / "dav"
        self.entries: dict[str, DavEntry] = {}
        self.reset()

    def reset(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)
        self.root.mkdir(parents=True)
        now = self.fixture.clock()
        self.entries = {"": DavEntry(True, self.collection_etag(), now, now)}

    def collection_etag(self) -> str:
        return f'"c{self.fixture.next_version()}"'

    def touch_parent(self, rel: str) -> None:
        parent = self.entries[parent_of(rel)]
        parent.etag = self.collection_etag()
        parent.modified = self.fixture.clock()

    def authorized(self, req: Request) -> bool:
        kind, _, encoded = req.header("Authorization").partition(" ")
        if kind.lower() != "basic":
            return False
        try:
            user, _, password = base64.b64decode(encoded.strip(), validate=True).decode("utf-8").partition(":")
        except ValueError:
            return False
        return secrets.compare_digest(user, self.fixture.dav_user) and \
            secrets.compare_digest(password, self.fixture.dav_password)

    def handle(self, req: Request) -> Reply:
        method = req.method
        req.note["kind"] = "dav." + (method.lower() if method in DAV_METHODS.split(", ") else "other")
        if not self.authorized(req):
            return text_reply(401, "authentication required\n",
                              {"WWW-Authenticate": 'Basic realm="saikou sync fixture", charset="UTF-8"'})
        rel = dav_rel(req.path)
        if rel is None:
            return text_reply(400, "a path segment is empty, . or ..\n")
        with self.fixture.lock:
            if method == "OPTIONS":
                return Reply(200, headers={"DAV": "1", "Allow": DAV_METHODS})
            if method == "PROPFIND":
                return self.propfind(req, rel)
            if method in ("GET", "HEAD"):
                return self.get(req, rel)
            if method == "PUT":
                return self.put(req, rel)
            if method == "DELETE":
                return self.delete(req, rel)
            if method == "MKCOL":
                return self.mkcol(req, rel)
        return text_reply(405, f"{method} is not served here\n", {"Allow": DAV_METHODS})

    def propfind(self, req: Request, rel: str) -> Reply:
        depth = req.header("Depth").strip().lower()
        if depth not in ("0", "1"):
            body = ('<?xml version="1.0" encoding="utf-8"?>\n'
                    '<d:error xmlns:d="DAV:"><d:propfind-finite-depth/></d:error>\n')
            return Reply(403, body.encode(), 'application/xml; charset="utf-8"')
        if req.body.strip():
            try:
                ElementTree.fromstring(req.body)
            except ElementTree.ParseError:
                return text_reply(400, "the PROPFIND body is not XML\n")
        entry = self.entries.get(rel)
        if entry is None:
            return text_reply(404, "no such resource\n")
        listed = [(rel, entry)]
        if depth == "1" and entry.collection:
            prefix = rel + "/" if rel else ""
            for child in sorted(self.entries):
                if child and parent_of(child) == rel and child.startswith(prefix):
                    if child == self.fixture.modes.hide:
                        req.note["injected"] = "hide"
                        continue
                    listed.append((child, self.entries[child]))
        out = ['<?xml version="1.0" encoding="utf-8"?>', '<d:multistatus xmlns:d="DAV:">']
        for child, item in listed:
            href = quote(DAV_PREFIX + child + ("/" if item.collection and child else ""))
            props = [
                f"<d:displayname>{escape(child.rsplit('/', 1)[-1])}</d:displayname>",
                "<d:resourcetype><d:collection/></d:resourcetype>" if item.collection else "<d:resourcetype/>",
                f"<d:getetag>{escape(item.etag)}</d:getetag>",
                f"<d:getlastmodified>{http_date(item.modified)}</d:getlastmodified>",
                f"<d:creationdate>{rfc3339(item.created)}</d:creationdate>",
            ]
            if not item.collection:
                props.append(f"<d:getcontentlength>{item.size}</d:getcontentlength>")
                props.append(f"<d:getcontenttype>{escape(item.content_type)}</d:getcontenttype>")
            out.append(f"<d:response><d:href>{escape(href)}</d:href><d:propstat><d:prop>{''.join(props)}"
                       "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        out.append("</d:multistatus>")
        return Reply(207, ("\n".join(out) + "\n").encode(), 'application/xml; charset="utf-8"', reason="Multi-Status")

    def get(self, req: Request, rel: str) -> Reply:
        entry = self.entries.get(rel)
        if entry is None:
            return text_reply(404, "no such resource\n")
        if entry.collection:
            return text_reply(405, "a collection has no body; PROPFIND lists it\n", {"Allow": DAV_METHODS})
        headers = {"ETag": entry.etag, "Last-Modified": http_date(entry.modified)}
        if req.method == "HEAD":
            headers["Content-Length"] = str(entry.size)
            return Reply(200, b"", entry.content_type, headers)
        return Reply(200, (self.root / rel).read_bytes(), entry.content_type, headers)

    def preconditions(self, req: Request, entry: DavEntry | None) -> Reply | None:
        if_match, if_none_match = req.header("If-Match"), req.header("If-None-Match")
        if if_match and not etag_matches(if_match, entry.etag if entry else None):
            return text_reply(412, "If-Match did not match\n")
        if if_none_match and etag_matches(if_none_match, entry.etag if entry else None):
            return text_reply(412, "If-None-Match matched\n")
        return None

    def put(self, req: Request, rel: str) -> Reply:
        entry = self.entries.get(rel)
        if rel == "" or (entry is not None and entry.collection):
            return text_reply(405, "PUT names a collection\n", {"Allow": DAV_METHODS})
        parent = self.entries.get(parent_of(rel))
        if parent is None or not parent.collection:
            return text_reply(409, "the parent collection does not exist\n")
        refused = self.preconditions(req, entry)
        if refused:
            return refused
        etag = content_etag(req.body)
        status = 201 if entry is None else 204
        if self.fixture.modes.lie:
            req.note["injected"] = "lie"
            return Reply(status, headers={"ETag": etag})
        now = self.fixture.clock()
        (self.root / rel).write_bytes(req.body)
        self.entries[rel] = DavEntry(False, etag, entry.created if entry else now, now, len(req.body),
                                     req.header("Content-Type") or "application/octet-stream")
        self.touch_parent(rel)
        return Reply(status, headers={"ETag": etag})

    def delete(self, req: Request, rel: str) -> Reply:
        if rel == "":
            return text_reply(403, "the root is not deleted\n")
        entry = self.entries.get(rel)
        if entry is None:
            return text_reply(404, "no such resource\n")
        refused = self.preconditions(req, entry)
        if refused:
            return refused
        for name in [name for name in self.entries if name == rel or name.startswith(rel + "/")]:
            del self.entries[name]
        target = self.root / rel
        if entry.collection:
            shutil.rmtree(target)
        else:
            target.unlink()
        self.touch_parent(rel)
        return Reply(204)

    def mkcol(self, req: Request, rel: str) -> Reply:
        if req.body:
            return text_reply(415, "MKCOL takes no body\n")
        if rel in self.entries:
            return text_reply(405, "the resource exists\n", {"Allow": DAV_METHODS})
        parent = self.entries.get(parent_of(rel))
        if parent is None or not parent.collection:
            return text_reply(409, "the parent collection does not exist\n")
        now = self.fixture.clock()
        (self.root / rel).mkdir()
        self.entries[rel] = DavEntry(True, self.collection_etag(), now, now)
        self.touch_parent(rel)
        return Reply(201)


# -- OAuth -------------------------------------------------------------------------------------------

@dataclass
class Code:
    redirect_uri: str
    challenge: str
    scope: str
    expires_at: float


@dataclass
class Grant:
    refresh_token: str
    scope: str
    expires_at: float | None
    revoked: bool = False


@dataclass
class Access:
    grant: Grant
    expires_at: float


def form_of(req: Request) -> dict[str, str] | None:
    """The form body's first values, blank ones left out as absent, or None when it is not a form."""
    if req.header("Content-Type").split(";", 1)[0].strip().lower() != "application/x-www-form-urlencoded":
        return None
    try:
        values = parse_qs(req.body.decode("utf-8"), strict_parsing=bool(req.body))
    except (UnicodeDecodeError, ValueError):
        return None
    return {key: items[0] for key, items in values.items() if items and items[0] != ""}


def loopback(uri: str) -> bool:
    """Whether a redirect URI is the plain HTTP loopback address a Desktop client may name, on any port."""
    try:
        split = urlsplit(uri)
        split.port
    except ValueError:
        return False
    return split.scheme == "http" and split.hostname in ("127.0.0.1", "localhost", "::1") and not split.fragment


class OAuth:
    """Google's authorization, token and revoke endpoints for one Desktop client and one account."""

    def __init__(self, fixture: Fixture):
        self.fixture = fixture
        self.codes: dict[str, Code] = {}
        self.grants: dict[str, Grant] = {}
        self.access: dict[str, Access] = {}

    def reset(self) -> None:
        self.codes, self.grants, self.access = {}, {}, {}

    def live(self, grant: Grant) -> bool:
        if grant.revoked or self.fixture.modes.revoked:
            return False
        return grant.expires_at is None or self.fixture.clock() < grant.expires_at

    def authorize(self, req: Request) -> Reply:
        req.note["kind"] = "oauth.authorize"
        if req.method != "GET":
            return text_reply(405, "the authorization endpoint answers GET\n", {"Allow": "GET"})
        q = req.query
        if q.get("client_id") != self.fixture.client_id:
            return oauth_error(401, "invalid_client", "The OAuth client was not found.")
        redirect_uri = q.get("redirect_uri", "")
        if not loopback(redirect_uri):
            return oauth_error(400, "redirect_uri_mismatch", "A Desktop client redirects to a loopback address.")
        if q.get("response_type") != "code":
            return oauth_error(400, "unsupported_response_type", "response_type must be code.")
        if not q.get("scope"):
            return oauth_error(400, "invalid_request", "Missing required parameter: scope")
        if q.get("code_challenge_method") != "S256" or not PKCE_CHALLENGE.match(q.get("code_challenge", "")):
            return oauth_error(400, "invalid_request", "The fixture requires an S256 code_challenge.")
        delay = self.fixture.modes.authorize_delay
        if delay is not None:
            # The owner reading the consent screen: real seconds, outside the lock so every other
            # request is served meanwhile, and cut short by a stop so it never holds one.
            req.note["injected"], req.note["delay_s"] = "delay", delay
            if self.fixture.stopping.wait(delay):
                return text_reply(503, "the fixture is stopping\n")
        with self.fixture.lock:
            code = "4/0" + secrets.token_urlsafe(32)
            self.codes[code] = Code(redirect_uri, q["code_challenge"], q["scope"],
                                    self.fixture.clock() + CODE_LIFETIME_S)
        answer = {"code": code, "scope": q["scope"]}
        if "state" in q:
            answer = {"state": q["state"], **answer}
        separator = "&" if urlsplit(redirect_uri).query else "?"
        return Reply(302, headers={"Location": redirect_uri + separator + urlencode(answer)})

    def token(self, req: Request) -> Reply:
        req.note["kind"] = "oauth.token"
        if req.method != "POST":
            return text_reply(405, "the token endpoint answers POST\n", {"Allow": "POST"})
        form = form_of(req)
        if form is None:
            return oauth_error(400, "invalid_request", "The body is not application/x-www-form-urlencoded.")
        grant_type = form.get("grant_type")
        secret = form.get("client_secret")
        req.note["grant"] = grant_type
        req.note["client_secret"] = ("missing" if secret is None else
                                     "present" if secrets.compare_digest(secret, self.fixture.client_secret)
                                     else "wrong")
        with self.fixture.lock:
            modes = self.fixture.modes
            if grant_type == "refresh_token" and modes.refresh_failures:
                failure = modes.refresh_failures.pop(0)
                req.note["injected"] = failure
                if failure == "drop":
                    return Reply(0, drop=True)
                return oauth_error(503, "temporarily_unavailable", "The service is currently unavailable.")
            if form.get("client_id") != self.fixture.client_id:
                return oauth_error(401, "invalid_client", "The OAuth client was not found.")
            if req.note["client_secret"] == "missing":
                return oauth_error(400, "invalid_request", "client_secret is missing.")
            if req.note["client_secret"] == "wrong":
                return oauth_error(401, "invalid_client", "Unauthorized")
            if grant_type == "authorization_code":
                return self.exchange(form)
            if grant_type == "refresh_token":
                return self.refresh(form)
            return oauth_error(400, "unsupported_grant_type", f"Invalid grant_type: {grant_type or ''}")

    def exchange(self, form: dict[str, str]) -> Reply:
        code = self.codes.pop(form.get("code", ""), None)
        if code is None or self.fixture.clock() >= code.expires_at:
            return oauth_error(400, "invalid_grant", "Malformed auth code.")
        if form.get("redirect_uri") != code.redirect_uri:
            return oauth_error(400, "redirect_uri_mismatch", "Bad Request")
        verifier = form.get("code_verifier")
        if verifier is None:
            return oauth_error(400, "invalid_grant", "Missing code verifier.")
        digest = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii", "replace")).digest())
        if not secrets.compare_digest(digest.rstrip(b"=").decode(), code.challenge):
            return oauth_error(400, "invalid_grant", "Invalid code verifier.")
        lifetime = self.fixture.modes.refresh_token_expires_in
        grant = Grant("1//0" + secrets.token_urlsafe(32), code.scope,
                      None if lifetime is None else self.fixture.clock() + lifetime)
        self.grants[grant.refresh_token] = grant
        return json_reply(200, {**self.issue(grant), "refresh_token": grant.refresh_token})

    def refresh(self, form: dict[str, str]) -> Reply:
        grant = self.grants.get(form.get("refresh_token", ""))
        if grant is None or not self.live(grant):
            return oauth_error(400, "invalid_grant", "Token has been expired or revoked.")
        return json_reply(200, self.issue(grant))

    def issue(self, grant: Grant) -> dict:
        token = "ya29.fixture-" + secrets.token_urlsafe(32)
        lifetime = self.fixture.modes.access_token_lifetime
        self.access[token] = Access(grant, self.fixture.clock() + lifetime)
        answer = {"access_token": token, "expires_in": lifetime, "scope": grant.scope, "token_type": "Bearer"}
        if grant.expires_at is not None:
            answer["refresh_token_expires_in"] = max(0, int(grant.expires_at - self.fixture.clock()))
        return answer

    def revoke(self, req: Request) -> Reply:
        req.note["kind"] = "oauth.revoke"
        if req.method != "POST":
            return text_reply(405, "the revoke endpoint answers POST\n", {"Allow": "POST"})
        token = (form_of(req) or {}).get("token") or req.query.get("token", "")
        with self.fixture.lock:
            grant = self.grants.get(token) or getattr(self.access.get(token), "grant", None)
            if grant is None or grant.revoked:
                return oauth_error(400, "invalid_token", "Token expired or revoked")
            grant.revoked = True
        return Reply(200, b"", "application/json; charset=utf-8")

    def expire_access_tokens(self) -> None:
        now = self.fixture.clock()
        for access in self.access.values():
            access.expires_at = min(access.expires_at, now)

    def authenticate(self, req: Request) -> Reply | None:
        """None when the request carries a live drive.appdata access token, else the answer Drive gives."""
        kind, _, token = req.header("Authorization").partition(" ")
        with self.fixture.lock:
            access = self.access.get(token.strip()) if kind.lower() == "bearer" else None
            if access is None or self.fixture.clock() >= access.expires_at or not self.live(access.grant):
                return unauthorized()
            if self.fixture.modes.early_401 > 0:
                self.fixture.modes.early_401 -= 1
                req.note["injected"] = "early401"
                return unauthorized()
        if DRIVE_APPDATA_SCOPE not in access.grant.scope.split():
            return drive_error(403, "insufficientPermissions", "Request had insufficient authentication scopes.")
        return None


# -- Drive v3, the appDataFolder -----------------------------------------------------------------------

@dataclass
class DriveFile:
    id: str
    name: str
    mime_type: str
    parent: str
    created: float
    modified: float
    version: int
    size: int | None = None
    md5: str | None = None
    sha256: str | None = None

    def resource(self) -> dict:
        out = {
            "kind": "drive#file", "id": self.id, "name": self.name, "mimeType": self.mime_type,
            "parents": [self.parent], "spaces": ["appDataFolder"], "trashed": False,
            "createdTime": rfc3339(self.created), "modifiedTime": rfc3339(self.modified),
            "version": str(self.version),
        }
        if self.size is not None:
            out.update(size=str(self.size), md5Checksum=self.md5, sha256Checksum=self.sha256)
        return out


@dataclass
class Upload:
    metadata: tuple[str, str, str]
    total: int | None
    path: Path
    spec: dict
    received: int = 0
    result: dict | None = None


Q_STRING = r"'((?:[^'\\]|\\.)*)'"
Q_TERMS = (
    ("parents", re.compile(r"\s*" + Q_STRING + r"\s+in\s+parents\s*")),
    ("name", re.compile(r"\s*name\s*(=|!=)\s*" + Q_STRING + r"\s*")),
    ("contains", re.compile(r"\s*name\s+contains\s+" + Q_STRING + r"\s*")),
    ("mimeType", re.compile(r"\s*mimeType\s*(=|!=)\s*" + Q_STRING + r"\s*")),
    ("trashed", re.compile(r"\s*trashed\s*=\s*(true|false)\s*")),
)
Q_AND = re.compile(r"and\s+", re.IGNORECASE)
ORDER_KEYS: dict[str, Callable[[DriveFile], object]] = {
    "createdTime": lambda f: f.created, "modifiedTime": lambda f: f.modified, "name": lambda f: f.name,
}


def parse_q(text: str, resolve: Callable[[str], str]) -> list[Callable[[DriveFile], bool]] | None:
    """The predicates of a q made of the supported terms joined by and, or None when it is not one."""
    predicates: list[Callable[[DriveFile], bool]] = []
    pos = 0
    while text.strip():
        for kind, pattern in Q_TERMS:
            match = pattern.match(text, pos)
            if match:
                break
        else:
            return None
        values = [re.sub(r"\\(.)", r"\1", group) for group in match.groups()]
        if kind == "parents":
            parent = resolve(values[0])
            predicates.append(lambda f, parent=parent: f.parent == parent)
        elif kind in ("name", "mimeType"):
            equal, value = values[0] == "=", values[1]
            attribute = "name" if kind == "name" else "mime_type"
            predicates.append(lambda f, a=attribute, v=value, eq=equal: (getattr(f, a) == v) == eq)
        elif kind == "contains":
            # Google documents contains on a name as a prefix match.
            predicates.append(lambda f, v=values[0]: f.name.startswith(v))
        else:
            predicates.append(lambda f, trashed=values[0] == "true": not trashed)
        pos = match.end()
        if pos == len(text):
            break
        joined = Q_AND.match(text, pos)
        if not joined:
            return None
        pos = joined.end()
    return predicates


def parse_order(text: str) -> list[tuple[Callable[[DriveFile], object], bool]] | None:
    order = []
    for item in (part.strip() for part in text.split(",")):
        if not item:
            continue
        words = item.split()
        if words[0] not in ORDER_KEYS or len(words) > 2 or (len(words) == 2 and words[1].lower() != "desc"):
            return None
        order.append((ORDER_KEYS[words[0]], len(words) == 2))
    return order


def parse_multipart(content_type: str, body: bytes) -> list[tuple[dict[str, str], bytes]] | str:
    """The parts of a multipart/related body as (lower-case headers, content), or what is wrong with it."""
    kind, _, params = content_type.partition(";")
    match = re.search(r'boundary="?([^";]+)"?', params)
    if kind.strip().lower() != "multipart/related" or not match:
        return "a multipart upload is multipart/related with a boundary"
    parts = []
    for section in body.split(b"--" + match.group(1).strip().encode())[1:]:
        if section.startswith(b"--"):
            break
        section = section[2:] if section.startswith(b"\r\n") else section
        if section.startswith(b"\r\n"):
            head, content = b"", section[2:]
        else:
            head, separator, content = section.partition(b"\r\n\r\n")
            if not separator:
                return "a part has no end to its headers"
        if content.endswith(b"\r\n"):
            content = content[:-2]
        headers = {}
        for line in head.decode("latin-1").split("\r\n"):
            name, colon, value = line.partition(":")
            if colon:
                headers[name.strip().lower()] = value.strip()
        parts.append((headers, content))
    else:
        return "the multipart body has no closing delimiter"
    if len(parts) != 2:
        return "a multipart upload is two parts, the metadata and the media"
    return parts


def page_token(offset: int) -> str:
    return base64.urlsafe_b64encode(json.dumps({"o": offset}).encode()).decode().rstrip("=")


def page_offset(token: str) -> int | None:
    try:
        offset = json.loads(base64.urlsafe_b64decode(token + "=" * (-len(token) % 4)))["o"]
    except (ValueError, TypeError, KeyError):
        return None
    return offset if type(offset) is int and offset >= 0 else None


class Drive:
    """The appDataFolder of one account: file records here, their bytes under <state>/drive."""

    def __init__(self, fixture: Fixture):
        self.fixture = fixture
        self.blobs = fixture.state_dir / "drive"
        self.upload_dir = fixture.state_dir / "uploads"
        # The folder's own id, which parents name; "appDataFolder" is only the alias a request may use.
        self.app_data_id = "0A" + secrets.token_urlsafe(24)
        self.files: dict[str, DriveFile] = {}
        self.uploads: dict[str, Upload] = {}
        self.reset()

    def reset(self) -> None:
        for directory in (self.blobs, self.upload_dir):
            shutil.rmtree(directory, ignore_errors=True)
            directory.mkdir(parents=True)
        self.files, self.uploads = {}, {}

    def resolve(self, file_id: str) -> str:
        return self.app_data_id if file_id == "appDataFolder" else file_id

    def handle(self, req: Request) -> Reply:
        path, method = req.path, req.method
        if path == UPLOAD_PATH and method == "PUT" and "upload_id" in req.query:
            req.note["kind"] = "drive.upload.chunk"
            # The session address is the credential, so no token is needed; one that is sent is
            # checked, since a client that sends an expired one must be ready for the 401.
            if req.header("Authorization"):
                denied = self.fixture.oauth.authenticate(req)
                if denied:
                    return denied
            with self.fixture.lock:
                return self.upload_chunk(req)
        file_id = path[len(DRIVE_FILES_PATH) + 1:] if path.startswith(DRIVE_FILES_PATH + "/") else None
        if file_id is not None and (not file_id or "/" in file_id):
            return not_found(req)
        routes = {
            DRIVE_FILES_PATH: {"GET": ("drive.files.list", self.list), "POST": ("drive.files.create", self.create)},
            DRIVE_ABOUT_PATH: {"GET": ("drive.about.get", self.about)},
            UPLOAD_PATH: {"POST": ("drive.files.create", self.upload)},
        }.get(path) or {"GET": ("drive.files.get", self.get), "DELETE": ("drive.files.delete", self.delete)}
        if method not in routes:
            req.note["kind"] = next(iter(routes.values()))[0]
            return text_reply(405, f"{method} is not served here\n", {"Allow": ", ".join(routes)})
        req.note["kind"], action = routes[method]
        denied = self.fixture.oauth.authenticate(req)
        if denied:
            return denied
        with self.fixture.lock:
            return action(req, file_id) if file_id is not None else action(req)

    def list(self, req: Request) -> Reply:
        spaces = {space.strip() for space in req.query.get("spaces", "drive").split(",")}
        if spaces != {"appDataFolder"}:
            return drive_error(403, "insufficientPermissions",
                               "The granted scopes do not give access to all of the requested spaces.", "spaces")
        size_text = req.query.get("pageSize")
        page_size = None
        if size_text is not None:
            if not size_text.isdigit() or not 1 <= int(size_text) <= MAX_PAGE_SIZE:
                return drive_error(400, "invalid", f"Invalid value '{size_text}'. Values must be within the range: "
                                                   f"[1, {MAX_PAGE_SIZE}]", "pageSize")
            page_size = int(size_text)
        limit = self.fixture.modes.drive_page_limit
        if limit is not None:
            page_size = min(page_size or limit, limit)
        offset = page_offset(req.query["pageToken"]) if req.query.get("pageToken") else 0
        if offset is None:
            return drive_error(400, "invalid", "Invalid Value", "pageToken")
        predicates = parse_q(req.query.get("q", ""), self.resolve)
        if predicates is None:
            return drive_error(400, "invalid", "Invalid Value", "q")
        order = parse_order(req.query.get("orderBy", ""))
        if order is None:
            return drive_error(400, "invalid", "Invalid Value", "orderBy")
        spec, refused = select_fields(req, LIST_SCHEMA, DEFAULT_LIST_SPEC)
        if refused:
            return refused
        matched = sorted((f for f in self.files.values() if all(p(f) for p in predicates)),
                         key=lambda f: (f.created, f.id))
        for key, descending in reversed(order):
            matched.sort(key=key, reverse=descending)
        hidden = self.fixture.modes.hide
        if hidden is not None and any(f.name == hidden for f in matched):
            req.note["injected"] = "hide"
            matched = [f for f in matched if f.name != hidden]
        end = len(matched) if page_size is None else min(len(matched), offset + page_size)
        body = {"kind": "drive#fileList", "incompleteSearch": False,
                "files": [f.resource() for f in matched[offset:end]]}
        if end < len(matched):
            body["nextPageToken"] = page_token(end)
        return json_reply(200, project(body, spec))

    def metadata(self, content_type: str, raw: bytes, fallback_mime: str) -> tuple[str, str, str] | Reply:
        """(name, mimeType, parent id) from a create's metadata, or Drive's answer to what is wrong."""
        value: object = {}
        if raw.strip():
            if not content_type.lower().startswith("application/json"):
                return drive_error(400, "badContent", f"Unsupported content with type: {content_type}")
            try:
                value = json.loads(raw)
            except ValueError:
                return drive_error(400, "parseError", "Parse Error")
        if not isinstance(value, dict):
            return drive_error(400, "parseError", "Parse Error")
        name, mime = value.get("name", "Untitled"), value.get("mimeType") or fallback_mime
        if not isinstance(name, str) or not isinstance(mime, str):
            return drive_error(400, "invalid", "Invalid Value")
        parents = value.get("parents")
        if not parents:
            # drive.appdata grants nothing outside the appDataFolder, and no parents means My Drive.
            return drive_error(403, "insufficientFilePermissions",
                               "The user does not have sufficient permissions for file root.")
        if not isinstance(parents, list) or len(parents) != 1 or not isinstance(parents[0], str):
            return drive_error(400, "invalid", "A file can only have one parent folder.", "parents")
        parent = self.resolve(parents[0])
        if parent != self.app_data_id:
            folder = self.files.get(parent)
            if folder is None:
                return drive_error(404, "notFound", f"File not found: {parents[0]}.", "parents")
            if folder.mime_type != FOLDER_MIME:
                return drive_error(400, "invalid", "The specified parent is not a folder.", "parents")
        return name, mime, parent

    def store(self, req: Request, name: str, mime: str, parent: str, content: bytes | None) -> dict:
        """Creates a file and answers its resource; in the lying mode, answers it and keeps nothing."""
        now = self.fixture.clock()
        created = DriveFile(secrets.token_urlsafe(25)[:33], name, mime, parent, now, now, self.fixture.next_version())
        if content is not None:
            created.size = len(content)
            created.md5 = hashlib.md5(content).hexdigest()
            created.sha256 = hashlib.sha256(content).hexdigest()
        if self.fixture.modes.lie:
            req.note["injected"] = "lie"
            return created.resource()
        if content is not None:
            (self.blobs / created.id).write_bytes(content)
        self.files[created.id] = created
        return created.resource()

    def create(self, req: Request) -> Reply:
        spec, refused = select_fields(req, FILE_SCHEMA, DEFAULT_FILE_SPEC)
        if refused:
            return refused
        meta = self.metadata(req.header("Content-Type"), req.body, "application/octet-stream")
        if isinstance(meta, Reply):
            return meta
        content = None if meta[1] == FOLDER_MIME else b""
        return json_reply(200, project(self.store(req, *meta, content), spec))

    def upload(self, req: Request) -> Reply:
        spec, refused = select_fields(req, FILE_SCHEMA, DEFAULT_FILE_SPEC)
        if refused:
            return refused
        upload_type = req.query.get("uploadType")
        if upload_type == "multipart":
            parts = parse_multipart(req.header("Content-Type"), req.body)
            if isinstance(parts, str):
                return drive_error(400, "badContent", parts)
            (meta_headers, meta_body), (media_headers, media_body) = parts
            meta = self.metadata(meta_headers.get("content-type", ""), meta_body,
                                 media_headers.get("content-type") or "application/octet-stream")
            if isinstance(meta, Reply):
                return meta
            return json_reply(200, project(self.store(req, *meta, media_body), spec))
        if upload_type == "resumable":
            meta = self.metadata(req.header("Content-Type"), req.body,
                                 req.header("X-Upload-Content-Type") or "application/octet-stream")
            if isinstance(meta, Reply):
                return meta
            declared = req.header("X-Upload-Content-Length").strip()
            if declared and not declared.isdigit():
                return drive_error(400, "invalid", "Invalid X-Upload-Content-Length.")
            upload_id = secrets.token_urlsafe(24)
            path = self.upload_dir / upload_id
            path.write_bytes(b"")
            self.uploads[upload_id] = Upload(meta, int(declared) if declared else None, path, spec)
            session = urlencode({"uploadType": "resumable", "upload_id": upload_id})
            location = f"http://{req.host}{UPLOAD_PATH}?{session}"
            return Reply(200, headers={"Location": location})
        return drive_error(400, "invalid", "The fixture serves uploadType multipart and resumable.", "uploadType")

    def incomplete(self, upload: Upload) -> Reply:
        headers = {"Range": f"bytes=0-{upload.received - 1}"} if upload.received else {}
        return Reply(308, headers=headers, reason="Resume Incomplete")

    def upload_chunk(self, req: Request) -> Reply:
        upload = self.uploads.get(req.query["upload_id"])
        if upload is None:
            return drive_error(404, "notFound", "The upload session was not found.")
        if upload.result is not None:
            return json_reply(200, upload.result)
        content_range = req.header("Content-Range").strip()
        if not content_range:
            # The whole file in one request, from the first byte.
            if upload.total is not None and len(req.body) != upload.total:
                return drive_error(400, "badRequest", "The body is not X-Upload-Content-Length long.")
            upload.path.write_bytes(b"")
            upload.received, start, end, total = 0, 0, len(req.body) - 1, len(req.body)
        else:
            match = re.fullmatch(r"bytes (\*|(\d+)-(\d+))/(\*|\d+)", content_range)
            if not match:
                return drive_error(400, "badRequest", "Failed to parse Content-Range header.")
            total = upload.total if match.group(4) == "*" else int(match.group(4))
            if upload.total is not None and total != upload.total:
                return drive_error(400, "badRequest", "The Content-Range total is not X-Upload-Content-Length.")
            if match.group(1) == "*":
                if req.body:
                    return drive_error(400, "badRequest", "A status query has no body.")
                if total == 0 and upload.received == 0:
                    return self.finish(req, upload)
                return self.incomplete(upload)
            start, end = int(match.group(2)), int(match.group(3))
            if end < start or len(req.body) != end - start + 1 or (total is not None and end >= total):
                return drive_error(400, "badRequest", "The Content-Range does not match the body.")
        req.note["range"] = [start, end, total]
        if start != upload.received:
            return self.incomplete(upload)
        final = total is not None and end + 1 == total
        kept = req.body if final else req.body[:(start + len(req.body)) // RESUMABLE_CHUNK * RESUMABLE_CHUNK - start]
        with upload.path.open("ab") as out:
            out.write(kept)
        upload.received += len(kept)
        return self.finish(req, upload) if final else self.incomplete(upload)

    def finish(self, req: Request, upload: Upload) -> Reply:
        upload.result = project(self.store(req, *upload.metadata, upload.path.read_bytes()), upload.spec)
        upload.path.unlink()
        return json_reply(200, upload.result)

    def get(self, req: Request, file_id: str) -> Reply:
        found = self.files.get(file_id)
        if found is None:
            return drive_error(404, "notFound", f"File not found: {file_id}.", "fileId")
        alt = req.query.get("alt", "json")
        if alt == "media":
            if found.size is None:
                return drive_error(403, "fileNotDownloadable", "Only files with binary content can be downloaded. "
                                   "Use Export with Docs Editors files.", "alt")
            return Reply(200, (self.blobs / found.id).read_bytes(), found.mime_type)
        if alt != "json":
            return drive_error(400, "invalid", "Invalid Value", "alt")
        spec, refused = select_fields(req, FILE_SCHEMA, DEFAULT_FILE_SPEC)
        return refused or json_reply(200, project(found.resource(), spec))

    def delete(self, req: Request, file_id: str) -> Reply:
        if self.resolve(file_id) == self.app_data_id:
            return drive_error(403, "insufficientFilePermissions",
                               f"The user does not have sufficient permissions for file {file_id}.")
        if file_id not in self.files:
            return drive_error(404, "notFound", f"File not found: {file_id}.", "fileId")
        doomed, frontier = set(), [file_id]
        while frontier:
            current = frontier.pop()
            doomed.add(current)
            frontier.extend(f.id for f in self.files.values() if f.parent == current)
        for gone in doomed:
            del self.files[gone]
            (self.blobs / gone).unlink(missing_ok=True)
        return Reply(204)

    def about(self, req: Request) -> Reply:
        spec, refused = select_fields(req, ABOUT_SCHEMA, None, required=True)
        if refused:
            return refused
        usage = str(sum(f.size or 0 for f in self.files.values()))
        body = {
            "kind": "drive#about",
            "user": {"kind": "drive#user", "displayName": "Fixture Owner", "me": True,
                     "permissionId": "fixture-permission-1", "emailAddress": self.fixture.account_email},
            "storageQuota": {"limit": str(15 * 1024 ** 3), "usage": usage, "usageInDrive": "0",
                             "usageInDriveTrash": "0"},
        }
        return json_reply(200, project(body, spec))


# -- The fixture, its server and its command line -------------------------------------------------------

CONTROL_METHODS = {"state": "GET", "modes": "POST", "expire-access-tokens": "POST", "reset": "POST"}


class Fixture:
    """Everything one run serves, keeping what the clients store in state_dir."""

    def __init__(self, state_dir: Path, *, dav_user: str = DEFAULT_DAV_USER, dav_password: str = DEFAULT_DAV_PASSWORD,
                 client_id: str = DEFAULT_CLIENT_ID, client_secret: str = DEFAULT_CLIENT_SECRET,
                 account_email: str = DEFAULT_ACCOUNT_EMAIL, access_token_lifetime: int = ACCESS_TOKEN_LIFETIME_S,
                 clock: Callable[[], float] = time.time, latency_ms: int = 0):
        self.state_dir = state_dir
        # Held before handling, outside the lock (--latency-ms); 0 holds nothing.
        self.latency_ms = latency_ms
        self.dav_user, self.dav_password = dav_user, dav_password
        self.client_id, self.client_secret = client_id, client_secret
        self.account_email = account_email
        self.access_token_lifetime = access_token_lifetime
        # Wall-clock seconds: token expiry and file times. Tests pass their own.
        self.clock = clock
        self.lock = threading.RLock()
        # Set when the server stops, so a request waiting on purpose (authorizeDelay) ends at once.
        self.stopping = threading.Event()
        self._version = 0
        self.modes = Modes(access_token_lifetime=access_token_lifetime)
        self.dav = Dav(self)
        self.oauth = OAuth(self)
        self.drive = Drive(self)

    def next_version(self) -> int:
        with self.lock:
            self._version += 1
            return self._version

    def handle(self, req: Request) -> Reply:
        path = req.path
        if path.startswith(CONTROL_PREFIX):
            return self.control(req)
        if path == DAV_PREFIX[:-1] or path.startswith(DAV_PREFIX):
            return self.dav.handle(req)
        if path == AUTH_PATH:
            return self.oauth.authorize(req)
        if path == TOKEN_PATH:
            return self.oauth.token(req)
        if path == REVOKE_PATH:
            return self.oauth.revoke(req)
        if path in (DRIVE_FILES_PATH, DRIVE_ABOUT_PATH, UPLOAD_PATH) or path.startswith(DRIVE_FILES_PATH + "/"):
            return self.drive.handle(req)
        return not_found(req)

    def control(self, req: Request) -> Reply:
        req.note["kind"] = "control"
        name = req.path[len(CONTROL_PREFIX):]
        if name not in CONTROL_METHODS:
            return not_found(req)
        if req.method != CONTROL_METHODS[name]:
            return text_reply(405, f"{name} answers {CONTROL_METHODS[name]}\n", {"Allow": CONTROL_METHODS[name]})
        with self.lock:
            if name == "modes":
                try:
                    values = json.loads(req.body or b"null")
                except ValueError:
                    return text_reply(400, "the body is not JSON\n")
                refused = self.modes.update(values)
                if refused:
                    return text_reply(400, refused + "\n")
                req.note["modes"] = values
            elif name == "expire-access-tokens":
                self.oauth.expire_access_tokens()
            elif name == "reset":
                self.modes = Modes(access_token_lifetime=self.access_token_lifetime)
                self.dav.reset()
                self.oauth.reset()
                self.drive.reset()
            return json_reply(200, self.state())

    def state(self) -> dict:
        return {
            "modes": self.modes.as_json(), "davResources": len(self.dav.entries) - 1,
            "driveFiles": len(self.drive.files), "uploads": len(self.drive.uploads),
            "grants": len(self.oauth.grants), "accessTokens": len(self.oauth.access), "stateDir": str(self.state_dir),
        }


class Handler(BaseHTTPRequestHandler):
    server: SyncFixtureServer
    protocol_version = "HTTP/1.1"
    wbufsize = 1 << 16

    def __getattr__(self, name: str):
        # BaseHTTPRequestHandler looks up do_<METHOD>. Every method, WebDAV's included, goes to one
        # dispatcher, so a path the fixture does not serve is 404 whatever the method.
        if name.startswith("do_"):
            return self.dispatch
        raise AttributeError(name)

    def setup(self) -> None:
        super().setup()
        self.server.connection_opened(self)

    def finish(self) -> None:
        try:
            super().finish()
        except OSError:
            # Part of an answer is still buffered for a client that is gone, or whose connection a
            # stop closed: dispatch has logged the request as aborted, and nothing is left to write to.
            self.rfile.close()
        finally:
            self.server.connection_closed(self)

    def parse_request(self) -> bool:
        # The request line is in: from here this connection is answering a request, and a stop waits
        # for the answer, its log line and whatever it stores.
        self.server.request_began(self)
        return super().parse_request()

    def handle_one_request(self) -> None:
        try:
            super().handle_one_request()
        except OSError:
            # The client went away, or a stop closed its stalled connection; a request that was read
            # is already logged as aborted, and the connection ends here instead of in a traceback.
            self.close_connection = True
        finally:
            if self.server.request_ended(self):
                self.close_connection = True

    def dispatch(self) -> None:
        t0 = time.monotonic()
        split = urlsplit(self.path)
        query = {key: values[0] for key, values in parse_qs(split.query, keep_blank_values=True).items()}
        host = self.headers.get("Host") or f"127.0.0.1:{self.server.server_address[1]}"
        req = Request(self.command, split.path, query, self.headers, b"", host)
        try:
            req.body = read_body(self.rfile, self.headers)
        except (ValueError, OSError):
            req.note["kind"] = "unreadable"
            reply = text_reply(400, "the request body could not be read\n")
            self.close_connection = True
        else:
            try:
                self.hold(req)
                reply = self.server.fixture.handle(req)
            except Exception as e:  # noqa: BLE001, a fixture defect answers 500 and is logged, never a hang
                req.note["error"] = f"{type(e).__name__}: {e}"
                reply = text_reply(500, f"the sync fixture failed: {req.note['error']}\n")
        aborted = False
        if reply.drop:
            self.close_connection = True
        else:
            try:
                self.answer(reply)
            except OSError:
                aborted = True
        self.server.log.write({
            "t0": t0, "t1": time.monotonic(), "method": self.command, "path": split.path,
            "kind": req.note.pop("kind", "unknown"), "status": None if reply.drop else reply.status,
            "aborted": aborted, "injected": req.note.pop("injected", None), **req.note,
        })

    def hold(self, req: Request) -> None:
        """The fixture's latency, before the request takes any lock; a control request is never held."""
        latency_ms = self.server.fixture.latency_ms
        if latency_ms <= 0 or req.path.startswith(CONTROL_PREFIX):
            return
        req.note["latency_ms"] = latency_ms
        self.server.fixture.stopping.wait(latency_ms / 1000)

    def answer(self, reply: Reply) -> None:
        self.send_response(reply.status, reply.reason)
        headers = dict(reply.headers)
        if reply.content_type:
            headers.setdefault("Content-Type", reply.content_type)
        headers.setdefault("Content-Length", str(len(reply.body)))
        for name, value in headers.items():
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(reply.body)
        self.wfile.flush()

    def log_message(self, format: str, *args) -> None:  # noqa: A002, the base class's signature
        pass  # the JSON log is the record; stderr stays quiet


class SyncFixtureServer(ThreadingHTTPServer):
    # ThreadingMixIn's server_close joins only request threads that are not daemons. Joined, no request
    # outlives the stop: main removes the state directory and closes the log after every answer's
    # line is written and every store it makes is done.
    daemon_threads = False
    # A request still being answered this long after a stop began has its connection closed.
    stop_grace_s = STOP_GRACE_S
    # Two devices' sync clients and the orchestrator may open several connections at once.
    request_queue_size = 64
    # A taken port fails to bind; it is never shared with the run that holds it.
    allow_reuse_port = False

    def __init__(self, address: tuple[str, int], log: RequestLog, fixture: Fixture):
        # Each open connection, and whether it is answering a request (else it waits for the next one).
        # Set before the bind, as a bind that fails calls server_close.
        self._connections: dict[Handler, bool] = {}
        # Notified when a request ends or a connection closes, which is what a stop waits for.
        self._connections_changed = threading.Condition()
        self._stopping = False
        self.fixture = fixture
        super().__init__(address, Handler)
        self.log = log

    def connection_opened(self, handler: Handler) -> None:
        with self._connections_changed:
            self._connections[handler] = False
            if self._stopping:
                end_reading(handler)

    def request_began(self, handler: Handler) -> None:
        with self._connections_changed:
            self._connections[handler] = True

    def request_ended(self, handler: Handler) -> bool:
        """Marks the connection idle; true when the server is stopping and the connection should close."""
        with self._connections_changed:
            self._connections[handler] = False
            self._connections_changed.notify_all()
            return self._stopping

    def connection_closed(self, handler: Handler) -> None:
        with self._connections_changed:
            self._connections.pop(handler, None)
            self._connections_changed.notify_all()

    def server_close(self) -> None:
        # A keep-alive connection between requests would hold its thread, and so the join, until the
        # client closes it: ending its reading ends the thread now. A connection answering a request
        # keeps it and closes after the answer. One whose request line arrived just as the stop began
        # may read the end of its stream before the rest of the request; it is answered from what
        # arrived and logged like any other. A request not answered within stop_grace_s has stalled
        # (a client frozen in the middle of its body, or one that stopped reading the answer), and
        # would hold the join for ever: its connection is closed both ways, so its read ends early
        # or its write fails, and it is logged as aborted before the join returns.
        self.fixture.stopping.set()
        with self._connections_changed:
            self._stopping = True
            for handler, busy in self._connections.items():
                if not busy:
                    end_reading(handler)
            self._connections_changed.wait_for(lambda: not any(self._connections.values()), self.stop_grace_s)
            for handler, busy in self._connections.items():
                if busy:
                    end_connection(handler)
        super().server_close()


def end_reading(handler: Handler) -> None:
    try:
        handler.connection.shutdown(socket.SHUT_RD)
    except OSError:
        pass  # already closed by the client


def end_connection(handler: Handler) -> None:
    try:
        handler.connection.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass  # already closed by the client


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--port", type=int, default=SERVER_PORT)
    # The emulator reaches the host's loopback as 10.0.2.2, so loopback is enough and nothing else
    # on the network can reach the fixture.
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--state-root", type=Path)
    parser.add_argument("--dav-user", default=DEFAULT_DAV_USER)
    parser.add_argument("--dav-password", default=DEFAULT_DAV_PASSWORD)
    parser.add_argument("--client-id", default=DEFAULT_CLIENT_ID)
    parser.add_argument("--client-secret", default=DEFAULT_CLIENT_SECRET)
    parser.add_argument("--account-email", default=DEFAULT_ACCOUNT_EMAIL)
    parser.add_argument("--access-token-lifetime", type=int, default=ACCESS_TOKEN_LIFETIME_S)
    parser.add_argument("--latency-ms", type=int, default=0)
    args = parser.parse_args(argv)
    # Blank is absent (R29): a blank credential would be a fixture that accepts a blank one.
    for option in ("dav_user", "dav_password", "client_id", "client_secret", "account_email"):
        if not getattr(args, option).strip():
            print(f"--{option.replace('_', '-')} is blank", file=sys.stderr)
            return 2
    if ":" in args.dav_user:
        print("--dav-user cannot hold a colon, which Basic authentication splits on", file=sys.stderr)
        return 2
    if args.access_token_lifetime < 1:
        print("--access-token-lifetime must be a positive number of seconds", file=sys.stderr)
        return 2
    if args.latency_ms < 0:
        print("--latency-ms must be 0 or more milliseconds", file=sys.stderr)
        return 2
    try:
        if args.state_root:
            args.state_root.mkdir(parents=True, exist_ok=True)
        state_dir = Path(tempfile.mkdtemp(prefix="sync-fixture-", dir=args.state_root))
    except OSError as e:
        print(f"cannot make the state directory: {e}", file=sys.stderr)
        return 2

    args.log.parent.mkdir(parents=True, exist_ok=True)
    log = RequestLog(args.log)
    started = False
    try:
        fixture = Fixture(state_dir, dav_user=args.dav_user, dav_password=args.dav_password,
                          client_id=args.client_id, client_secret=args.client_secret,
                          account_email=args.account_email, access_token_lifetime=args.access_token_lifetime,
                          latency_ms=args.latency_ms)
        try:
            server = SyncFixtureServer((args.bind, args.port), log, fixture)
        except OSError as e:
            print(f"cannot listen on {args.bind}:{args.port}: {e}; is another run holding it?", file=sys.stderr)
            return 3

        def stop(*_):
            threading.Thread(target=server.shutdown, daemon=True).start()

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        port = server.server_address[1]
        log.write({
            "event": "start", "monotonic": time.monotonic(),
            "wall": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
            "port": port, "state_dir": str(state_dir), "dav_user": args.dav_user, "client_id": args.client_id,
            "account_email": args.account_email, "access_token_lifetime": args.access_token_lifetime,
            "latency_ms": args.latency_ms,
        })
        started = True
        print(f"ready {port}", flush=True)
        try:
            server.serve_forever(poll_interval=0.2)
        finally:
            server.server_close()
        return 0
    finally:
        shutil.rmtree(state_dir, ignore_errors=True)
        if started:
            log.write({"event": "stop", "monotonic": time.monotonic(), "state_removed": not state_dir.exists()})
        log.close()


if __name__ == "__main__":
    sys.exit(main())
