#!/usr/bin/env python3
"""The host-side fake of MyAnimeList's OAuth endpoints and the part of its v2 API the app calls
(phase 3 unit T2), for the device's browser sign-in and its progress push.

It follows sync_fixture_server.py, whose request, reply, form and loopback helpers it reuses: loopback
only, on --port (0 by default, so the system picks one; the emulator reaches it as 10.0.2.2), "ready
<port>" on stdout once it listens, exit 3 when the port is taken, one JSON line per request in --log,
and a stop on SIGTERM or SIGINT. It never binds 18796, the tracker loopback port the app's sign-in
listens on inside the emulator (exit 2), nor the sync fixture's 18795 or the update fixture's 18794.

The app reaches it through the `saikou.mal.base` runner argument: a debug build then sends every
MyAnimeList call here with the fixture's client id, `saikou-mal-fixture-client`, keeping the paths.

    GET  /v1/oauth2/authorize   the authorization page, already approved. It needs the fixture's
                                client_id, response_type=code, code_challenge_method=plain (MyAnimeList's
                                only method), a code_challenge of 43 to 128 unreserved characters, a
                                plain HTTP loopback redirect_uri and a state, and answers 302 to the
                                redirect_uri with code and state. Anything missing or wrong is 400.
    POST /v1/oauth2/token       form encoded, with the fixture's client_id (else 401 invalid_client).
                                authorization_code: the code once, before it expires, with the same
                                redirect_uri and a code_verifier equal to the challenge (plain);
                                refresh_token: a live refresh token, which is replaced, as MyAnimeList
                                replaces it. A code or refresh token that fails is 400 invalid_grant.
                                Tokens answer as MyAnimeList's do: token_type, expires_in,
                                access_token, refresh_token.
    GET  /v2/users/@me                        the account, named --user-name
    GET  /v2/manga?q=                         the catalogue entries whose title holds q
    GET  /v2/manga/<id>                       an entry, with my_list_status while the list has it
    PUT  /v2/manga/<id>/my_list_status        form encoded; stores the list status and answers it
    DELETE /v2/manga/<id>/my_list_status      200 and [], or 404 when the list lacks it
    GET  /v2/users/@me/mangalist              the list by entry id, a page of `limit` entries (10 when
                                              absent) from `offset` (0), capped at --list-page-size;
                                              paging.next, while entries remain, is a URL on the
                                              request's host with the next entry's index as its offset;
                                              each entry's list_status only when `fields` names it
                                              (phase 4 unit TC)

A /v2 call needs "Authorization: Bearer <live access token>", else 401 invalid_token. An unknown
entry is 404, and so is any other path with any method.

One JSON line per request, written once its answer is:

    {"t0": <monotonic seconds at receipt>, "t1": <after the answer>, "method", "path", "kind",
     "status", "aborted", ...}

with the path and never its query, but for a list.get line, which adds the four query values the
comparison's paging depends on: "offset", "limit", "fields" and "nsfw" (each null when absent; none
of them is a token, code, verifier or state). Tokens, codes, verifiers and states are never logged. An
oauth.token line adds "grant"; a list.put line adds "form" (the list fields the app sent: status,
is_rereading, score, num_chapters_read, start_date, finish_date) and "entry"; list.delete and
manga.get add "entry"; a refusal adds "refused" (its OAuth error, or what was wrong). The first line
is {"event": "start", ...}, the last {"event": "stop", ...}.

Usage: mal_fixture_server.py --log <file> [--port N] [--bind ADDRESS] [--client-id ID]
           [--user-name NAME] [--access-token-lifetime SECONDS] [--list-page-size N]
Exit codes: 0 stopped; 2 usage; 3 the port could not be bound.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import secrets
import signal
import sys
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, unquote, urlencode, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sync_fixture_server import (  # noqa: E402
    Reply,
    Request,
    form_of,
    json_reply,
    loopback,
    read_body,
    text_reply,
)
# The frozen update fixture's log writer (R66), reused unchanged: whole lines, under a lock.
from update_fixture_server import RequestLog  # noqa: E402

DEFAULT_CLIENT_ID = "saikou-mal-fixture-client"
DEFAULT_USER_NAME = "fixture-reader"
USER_ID = 4242
# MyAnimeList's access tokens last 31 days.
ACCESS_TOKEN_LIFETIME_S = 2_678_400
CODE_LIFETIME_S = 600
# Ports the fake never binds: the tracker loopback inside the emulator, the sync and update fixtures.
RESERVED_PORTS = {18796: "the tracker loopback port", 18795: "the sync fixture's", 18794: "the update fixture's"}

AUTHORIZE_PATH = "/v1/oauth2/authorize"
TOKEN_PATH = "/v1/oauth2/token"
API_PREFIX = "/v2/"
PKCE_PLAIN = re.compile(r"^[A-Za-z0-9._~-]{43,128}$")
MANGA_PATH = re.compile(r"^/v2/manga/(\d+)$")
LIST_STATUS_PATH = re.compile(r"^/v2/manga/(\d+)/my_list_status$")
LIST_STATUSES = ("reading", "completed", "on_hold", "dropped", "plan_to_read")
LIST_FORM_FIELDS = ("status", "is_rereading", "score", "num_chapters_read", "start_date", "finish_date")
# The list read's query values a list.get line carries, and its page size when the request names none.
LIST_QUERY_LOGGED = ("offset", "limit", "fields", "nsfw")
DEFAULT_LIST_LIMIT = 10

# The catalogue: enough for a bind, a push, a search that filters out a novel, an entry with no
# chapter count, and a third manga for the comparison's device check (phase 4 unit TC).
CATALOGUE = {
    1001: {"title": "Fixture Title", "num_chapters": 12, "media_type": "manga", "status": "finished"},
    1002: {"title": "Fixture Title Novel", "num_chapters": 40, "media_type": "light_novel", "status": "finished"},
    1003: {"title": "Fixture Ongoing", "num_chapters": 0, "media_type": "manga", "status": "currently_publishing"},
    1004: {"title": "Fixture Sequel", "num_chapters": 12, "media_type": "manga", "status": "finished"},
}


def oauth_error(status: int, error: str, message: str) -> Reply:
    """MyAnimeList's token errors carry error, message and hint; the hint is left out."""
    return json_reply(status, {"error": error, "message": message})


def api_error(status: int, error: str) -> Reply:
    return json_reply(status, {"error": error, "message": error.replace("_", " ")})


def manga_json(entry_id: int) -> dict:
    entry = CATALOGUE[entry_id]
    return {
        "id": entry_id, "title": entry["title"], "synopsis": "A fixture entry.", "mean": 7.5,
        "num_chapters": entry["num_chapters"], "status": entry["status"], "media_type": entry["media_type"],
        "start_date": "2020-01-01", "main_picture": {"large": f"https://cdn.fixture.invalid/{entry_id}.jpg"},
        "authors": [{"node": {"id": 1, "first_name": "Fixture", "last_name": "Author"}, "role": "Story & Art"}],
    }


@dataclass
class Code:
    redirect_uri: str
    challenge: str
    expires_at: float


class Fixture:
    """One account, one client, one list, all in memory."""

    def __init__(self, *, client_id: str = DEFAULT_CLIENT_ID, user_name: str = DEFAULT_USER_NAME,
                 access_token_lifetime: int = ACCESS_TOKEN_LIFETIME_S, clock: Callable[[], float] = time.time,
                 list_page_size: int | None = None):
        self.client_id = client_id
        # The most entries one page of the list read answers, whatever limit asks; None: no cap.
        self.list_page_size = list_page_size
        self.user_name = user_name
        self.access_token_lifetime = access_token_lifetime
        self.clock = clock
        self.lock = threading.Lock()
        self.codes: dict[str, Code] = {}
        self.refresh_tokens: set[str] = set()
        # Access token to its expiry.
        self.access: dict[str, float] = {}
        # Entry id to its list status, as MyAnimeList answers it.
        self.entries: dict[int, dict] = {}

    def handle(self, req: Request) -> Reply:
        if req.path == AUTHORIZE_PATH:
            return self.authorize(req)
        if req.path == TOKEN_PATH:
            return self.token(req)
        if req.path.startswith(API_PREFIX):
            return self.api(req)
        req.note["kind"] = "unknown"
        return text_reply(404, "no such path on the MyAnimeList fixture\n")

    # -- OAuth -------------------------------------------------------------------------------------

    def authorize(self, req: Request) -> Reply:
        req.note["kind"] = "oauth.authorize"
        if req.method != "GET":
            return self.refuse(req, text_reply(405, "GET only\n", {"Allow": "GET"}), "method")
        q = req.query
        wrong = (
            "client_id" if q.get("client_id") != self.client_id
            else "response_type" if q.get("response_type") != "code"
            else "code_challenge_method" if q.get("code_challenge_method") != "plain"
            else "code_challenge" if not PKCE_PLAIN.match(q.get("code_challenge", ""))
            else "redirect_uri" if not loopback(q.get("redirect_uri", ""))
            else "state" if not q.get("state")
            else None
        )
        if wrong:
            return self.refuse(req, text_reply(400, f"the authorization request has no valid {wrong}\n"), wrong)
        code = secrets.token_urlsafe(24)
        with self.lock:
            self.codes[code] = Code(q["redirect_uri"], q["code_challenge"], self.clock() + CODE_LIFETIME_S)
        separator = "&" if urlsplit(q["redirect_uri"]).query else "?"
        location = q["redirect_uri"] + separator + urlencode({"code": code, "state": q["state"]})
        return Reply(302, b"", None, {"Location": location, "Cache-Control": "no-store"})

    def token(self, req: Request) -> Reply:
        req.note["kind"] = "oauth.token"
        if req.method != "POST":
            return self.refuse(req, text_reply(405, "POST only\n", {"Allow": "POST"}), "method")
        form = form_of(req)
        if form is None:
            return self.refuse(req, oauth_error(400, "invalid_request", "The body is not a form."), "invalid_request")
        grant = form.get("grant_type")
        req.note["grant"] = grant
        if form.get("client_id") != self.client_id:
            return self.refuse(req, oauth_error(401, "invalid_client", "Client authentication failed"), "invalid_client")
        with self.lock:
            if grant == "authorization_code":
                code = self.codes.pop(form.get("code", ""), None)
                if code is None or self.clock() >= code.expires_at:
                    return self.refuse(req, self.invalid_grant(), "code")
                if form.get("redirect_uri") != code.redirect_uri:
                    return self.refuse(req, self.invalid_grant(), "redirect_uri")
                if form.get("code_verifier") != code.challenge:
                    return self.refuse(req, self.invalid_grant(), "code_verifier")
            elif grant == "refresh_token":
                refresh = form.get("refresh_token", "")
                if refresh not in self.refresh_tokens:
                    return self.refuse(req, self.invalid_grant(), "refresh_token")
                self.refresh_tokens.discard(refresh)
            else:
                return self.refuse(req, oauth_error(400, "unsupported_grant_type", "The grant is not supported."),
                                   "unsupported_grant_type")
            access, refresh = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
            self.access[access] = self.clock() + self.access_token_lifetime
            self.refresh_tokens.add(refresh)
        return json_reply(200, {
            "token_type": "Bearer", "expires_in": self.access_token_lifetime,
            "access_token": access, "refresh_token": refresh,
        })

    @staticmethod
    def invalid_grant() -> Reply:
        return oauth_error(400, "invalid_grant", "The provided authorization grant is invalid.")

    @staticmethod
    def refuse(req: Request, reply: Reply, why: str) -> Reply:
        req.note["refused"] = why
        return reply

    # -- The v2 API --------------------------------------------------------------------------------

    def api(self, req: Request) -> Reply:
        path = unquote(req.path)
        header = req.header("Authorization")
        token = header[len("Bearer "):] if header.startswith("Bearer ") else ""
        with self.lock:
            expires_at = self.access.get(token)
            if expires_at is None or self.clock() >= expires_at:
                req.note["kind"] = "api.unauthorized"
                return Reply(401, json.dumps({"error": "invalid_token"}).encode(), "application/json; charset=UTF-8",
                             {"WWW-Authenticate": 'Bearer error="invalid_token"'})
            if path == "/v2/users/@me" and req.method == "GET":
                req.note["kind"] = "user.get"
                return json_reply(200, {"id": USER_ID, "name": self.user_name, "joined_at": "2020-01-01T00:00:00+00:00"})
            if path == "/v2/users/@me/mangalist" and req.method == "GET":
                req.note["kind"] = "list.get"
                return self.list_page(req)
            if path == "/v2/manga" and req.method == "GET":
                req.note["kind"] = "manga.search"
                query = req.query.get("q", "").lower()
                data = [{"node": manga_json(i)} for i in sorted(CATALOGUE) if query in CATALOGUE[i]["title"].lower()]
                return json_reply(200, {"data": data, "paging": {}})
            if match := MANGA_PATH.match(path):
                req.note["kind"] = "manga.get"
                return self.manga(req, int(match.group(1)))
            if match := LIST_STATUS_PATH.match(path):
                req.note["kind"] = "list.put" if req.method == "PUT" else "list.delete"
                return self.list_status(req, int(match.group(1)))
        req.note["kind"] = "unknown"
        return api_error(404, "not_found")

    def list_page(self, req: Request) -> Reply:
        """One page of the list: `limit` entries from `offset`, capped at the page size, by entry id."""
        q = req.query
        for key in LIST_QUERY_LOGGED:
            req.note[key] = q.get(key)
        try:
            offset = int(q.get("offset", "0"))
            limit = int(q.get("limit", str(DEFAULT_LIST_LIMIT)))
            if offset < 0 or limit < 1:
                raise ValueError("paging")
        except ValueError:
            return self.refuse(req, api_error(400, "invalid_parameters"), "paging")
        size = min(limit, self.list_page_size) if self.list_page_size else limit
        with_status = "list_status" in q.get("fields", "").split(",")
        ids = sorted(self.entries)
        page = ids[offset:offset + size]
        data = []
        for entry_id in page:
            item = {"node": manga_json(entry_id)}
            if with_status:
                item["list_status"] = dict(self.entries[entry_id])
            data.append(item)
        paging = {}
        following = offset + len(page)
        if following < len(ids):
            paging["next"] = f"http://{req.host}{req.path}?" + urlencode({**q, "offset": str(following)})
        return json_reply(200, {"data": data, "paging": paging})

    def manga(self, req: Request, entry_id: int) -> Reply:
        req.note["entry"] = entry_id
        if req.method != "GET":
            return api_error(405, "method_not_allowed")
        if entry_id not in CATALOGUE:
            return api_error(404, "not_found")
        body = manga_json(entry_id)
        if entry_id in self.entries:
            body["my_list_status"] = dict(self.entries[entry_id])
        return json_reply(200, body)

    def list_status(self, req: Request, entry_id: int) -> Reply:
        req.note["entry"] = entry_id
        if req.method not in ("PUT", "DELETE"):
            return api_error(405, "method_not_allowed")
        if entry_id not in CATALOGUE:
            return api_error(404, "not_found")
        if req.method == "DELETE":
            if self.entries.pop(entry_id, None) is None:
                return api_error(404, "not_found")
            return json_reply(200, [])
        form = form_of(req)
        if form is None:
            return self.refuse(req, api_error(400, "invalid_parameters"), "form")
        req.note["form"] = {key: form.get(key) for key in LIST_FORM_FIELDS}
        status = self.entries.get(entry_id, {"status": "plan_to_read", "is_rereading": False, "score": 0,
                                             "num_chapters_read": 0})
        status = dict(status)
        try:
            if "status" in form:
                if form["status"] not in LIST_STATUSES:
                    raise ValueError("status")
                status["status"] = form["status"]
            if "is_rereading" in form:
                status["is_rereading"] = form["is_rereading"] == "true"
            if "score" in form:
                status["score"] = int(float(form["score"]))
            if "num_chapters_read" in form:
                status["num_chapters_read"] = int(form["num_chapters_read"])
            # A blank date is absent (form_of drops it): MyAnimeList leaves the field out then.
            for key in ("start_date", "finish_date"):
                if key in form:
                    datetime.date.fromisoformat(form[key])
                    status[key] = form[key]
        except ValueError:
            return self.refuse(req, api_error(400, "invalid_parameters"), "list fields")
        status["updated_at"] = datetime.datetime.fromtimestamp(self.clock(), datetime.timezone.utc).isoformat()
        self.entries[entry_id] = status
        return json_reply(200, status)


class Handler(BaseHTTPRequestHandler):
    server: MalFixtureServer
    protocol_version = "HTTP/1.1"

    def __getattr__(self, name: str):
        # Every method goes to one dispatcher, so a path the fake does not serve is 404 whatever the method.
        if name.startswith("do_"):
            return self.dispatch
        raise AttributeError(name)

    def dispatch(self) -> None:
        t0 = time.monotonic()
        split = urlsplit(self.path)
        query = {key: values[0] for key, values in parse_qs(split.query, keep_blank_values=True).items()}
        req = Request(self.command, split.path, query, self.headers, b"", self.headers.get("Host") or "")
        try:
            req.body = read_body(self.rfile, self.headers)
        except (ValueError, OSError):
            req.note["kind"] = "unreadable"
            reply = text_reply(400, "the request body could not be read\n")
            self.close_connection = True
        else:
            try:
                reply = self.server.fixture.handle(req)
            except Exception as e:  # noqa: BLE001, a fixture defect answers 500 and is logged, never a hang
                req.note["error"] = type(e).__name__
                reply = text_reply(500, "the MyAnimeList fixture failed\n")
        aborted = False
        try:
            self.send_response(reply.status)
            headers = dict(reply.headers)
            if reply.content_type:
                headers.setdefault("Content-Type", reply.content_type)
            headers.setdefault("Content-Length", str(len(reply.body)))
            for name, value in headers.items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(reply.body)
            self.wfile.flush()
        except OSError:
            aborted = True
            self.close_connection = True
        self.server.log.write({
            "t0": t0, "t1": time.monotonic(), "method": self.command, "path": split.path,
            "kind": req.note.pop("kind", "unknown"), "status": reply.status, "aborted": aborted, **req.note,
        })

    def log_message(self, format: str, *args) -> None:  # noqa: A002, the base class's signature
        pass  # the JSON log is the record; stderr stays quiet


class MalFixtureServer(ThreadingHTTPServer):
    # A browser opens speculative connections that send nothing; none of them may hold the stop.
    daemon_threads = True
    # A taken port fails to bind; it is never shared with the run that holds it.
    allow_reuse_port = False

    def __init__(self, address: tuple[str, int], log: RequestLog, fixture: Fixture):
        self.fixture = fixture
        self.log = log
        super().__init__(address, Handler)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--port", type=int, default=0)
    # The emulator reaches the host's loopback as 10.0.2.2, so nothing else on the network can reach it.
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--client-id", default=DEFAULT_CLIENT_ID)
    parser.add_argument("--user-name", default=DEFAULT_USER_NAME)
    parser.add_argument("--access-token-lifetime", type=int, default=ACCESS_TOKEN_LIFETIME_S)
    # The comparison's device check pages one entry at a time (phase 4 unit TC).
    parser.add_argument("--list-page-size", type=int, default=None)
    args = parser.parse_args(argv)
    if args.port in RESERVED_PORTS:
        print(f"--port {args.port} is {RESERVED_PORTS[args.port]}; the fake never binds it", file=sys.stderr)
        return 2
    for option in ("client_id", "user_name"):
        if not getattr(args, option).strip():
            print(f"--{option.replace('_', '-')} is blank", file=sys.stderr)
            return 2
    if args.access_token_lifetime < 1:
        print("--access-token-lifetime must be a positive number of seconds", file=sys.stderr)
        return 2
    if args.list_page_size is not None and args.list_page_size < 1:
        print("--list-page-size must be a positive number of entries", file=sys.stderr)
        return 2

    args.log.parent.mkdir(parents=True, exist_ok=True)
    log = RequestLog(args.log)
    try:
        fixture = Fixture(client_id=args.client_id, user_name=args.user_name,
                          access_token_lifetime=args.access_token_lifetime, list_page_size=args.list_page_size)
        try:
            server = MalFixtureServer((args.bind, args.port), log, fixture)
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
            "port": port, "client_id": args.client_id, "user_name": args.user_name,
            "access_token_lifetime": args.access_token_lifetime, "list_page_size": args.list_page_size,
        })
        print(f"ready {port}", flush=True)
        try:
            server.serve_forever(poll_interval=0.2)
        finally:
            server.server_close()
        log.write({"event": "stop", "monotonic": time.monotonic(), "list": fixture.entries})
        return 0
    finally:
        log.close()


if __name__ == "__main__":
    sys.exit(main())
