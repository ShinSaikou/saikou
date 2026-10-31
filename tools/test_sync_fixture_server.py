#!/usr/bin/env python3
"""Unit tests for tools/bench/sync_fixture_server.py, the sync fixture of phase3-start.md unit D.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The server runs in process on a free port with a clock the tests move, as test_download_hold_server.py
runs its server, and is asked what the app will ask: WebDAV with Basic authentication, the sign-in with
no browser, the token endpoint's two grants, the Drive calls. The command line runs as a subprocess, so
its ready line, its refusal of a taken port and its cleanup are the real ones.

The cases that matter, and why each one is here:
  - WebDAV: authentication, the round trip of every method with its ETag, a chunked PUT (OkHttp's
    body of unknown length), the conditions, the lying mode that stores nothing and the mode that hides
    one file from a listing, since F's read-back check and E's chain rules are proved against them;
  - OAuth: a code from the authorization endpoint with no browser, the client secret required on both
    grants as Google requires it of a Desktop client, the PKCE check, access tokens that expire, the
    refresh failure sequence, the early 401, the revoked mode and the revoke endpoint, and
    refresh_token_expires_in; the delay mode that holds the authorization answer while everything else
    is served, as unit O's spike waits on it; and the 14-day scenario read from the log the way I's
    check reads it;
  - Drive: about.get, files.list with its space, paging, q and fields, multipart and resumable create
    (the 308 flow keeping only whole 256 KiB chunks, and a session PUT that needs no token but has one
    it carries checked), get with alt=media, delete, and the two modes;
  - every unknown path is 404 whatever the method, the log never holds a secret, and the command line
    prints its ready line, refuses a taken port with exit 3, and removes its state when it stops;
  - a stop waits for a request being answered, its log line and its store, and closes a connection
    waiting for its next request, since I's checks read the log and the state after a stop; and it
    closes a stalled request once its grace is over, since the orchestrator's stop has no kill after it,
    and ends an authorization the delay mode is holding at once;
  - the latency knob (phase 5 unit SC3) holds nothing by default, and holds each request before the
    lock, so two parallel requests overlap their latency instead of adding it up.
"""

from __future__ import annotations

import base64
import contextlib
import hashlib
import http.client
import importlib.util
import io
import json
import os
import secrets
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.parse import parse_qs, quote, urlencode, urlsplit

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS / "bench"
SERVER = BENCH / "sync_fixture_server.py"
sys.path.insert(0, str(BENCH))
_spec = importlib.util.spec_from_file_location("sync_fixture_server", SERVER)
srv = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = srv
_spec.loader.exec_module(srv)

REDIRECT = "http://127.0.0.1:41999/"
DAY_S = 24 * 60 * 60


class Clock:
    def __init__(self, now: float = 1_790_000_000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def multipart(metadata: dict, media: bytes, media_type: str = "application/gzip") -> tuple[bytes, dict]:
    boundary = "fixture-boundary-7"
    body = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{json.dumps(metadata)}\r\n"
            f"--{boundary}\r\nContent-Type: {media_type}\r\n\r\n").encode() + media + f"\r\n--{boundary}--\r\n".encode()
    return body, {"Content-Type": f"multipart/related; boundary={boundary}"}


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.clock = Clock()
        self.fixture = srv.Fixture(Path(self.tmp.name, "state"), clock=self.clock)
        self.log_path = Path(self.tmp.name, "requests.jsonl")
        self.log = srv.RequestLog(self.log_path)
        self.server = srv.SyncFixtureServer(("127.0.0.1", 0), self.log, self.fixture)
        self.port = self.server.server_address[1]
        self.sent = 0
        threading.Thread(target=self.server.serve_forever, args=(0.05,), daemon=True).start()
        self.addCleanup(self.close)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.log.close()

    # -- plumbing

    def request(self, method: str, path: str, body=b"", headers: dict | None = None,
                chunked: bool = False) -> tuple[int, dict, bytes]:
        self.sent += 1
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {}, encode_chunked=chunked)
            response = connection.getresponse()
            return response.status, {k.lower(): v for k, v in response.getheaders()}, response.read()
        finally:
            connection.close()

    def records(self, kind: str | None = None) -> list[dict]:
        # The server logs a request after its answer is written, so a client can read the answer first:
        # wait until every request sent so far has its line. A line is written when its answer is, and
        # the handler of one request may finish after the next one's, so the order is t0's.
        deadline = time.monotonic() + 5
        while True:
            lines = [json.loads(line) for line in self.log_path.read_text().splitlines()]
            if len(lines) >= self.sent or time.monotonic() > deadline:
                break
            time.sleep(0.01)
        return sorted((r for r in lines if kind is None or r["kind"] == kind), key=lambda r: r["t0"])

    def control(self, name: str, value=None) -> dict:
        method = "GET" if name == "state" else "POST"
        body = b"" if value is None else json.dumps(value).encode()
        status, _, answer = self.request(method, srv.CONTROL_PREFIX + name, body, {"Content-Type": "application/json"})
        self.assertEqual(status, 200, answer)
        return json.loads(answer)

    def dav(self, method: str, rel: str, body=b"", headers: dict | None = None, password: str | None = None,
            chunked: bool = False) -> tuple[int, dict, bytes]:
        credentials = f"{srv.DEFAULT_DAV_USER}:{password or srv.DEFAULT_DAV_PASSWORD}".encode()
        all_headers = {"Authorization": "Basic " + base64.b64encode(credentials).decode(), **(headers or {})}
        return self.request(method, srv.DAV_PREFIX + quote(rel), body, all_headers, chunked)

    def listing(self, rel: str = "", depth: str = "1") -> list[str]:
        status, _, body = self.dav("PROPFIND", rel, headers={"Depth": depth})
        self.assertEqual(status, 207, body)
        text = body.decode()
        return [part.split("</d:href>")[0] for part in text.split("<d:href>")[1:]]

    def form(self, path: str, values: dict) -> tuple[int, dict, bytes]:
        return self.request("POST", path, urlencode(values).encode(),
                            {"Content-Type": "application/x-www-form-urlencoded"})

    def authorize(self, scope: str = srv.DRIVE_APPDATA_SCOPE, **overrides) -> tuple[int, dict, str]:
        verifier, challenge = pkce()
        query = {"client_id": srv.DEFAULT_CLIENT_ID, "redirect_uri": REDIRECT, "response_type": "code",
                 "scope": scope, "code_challenge": challenge, "code_challenge_method": "S256", "state": "st-1"}
        query.update(overrides)
        status, headers, _ = self.request("GET", srv.AUTH_PATH + "?" + urlencode(query))
        return status, headers, verifier

    def sign_in(self, scope: str = srv.DRIVE_APPDATA_SCOPE) -> dict:
        status, headers, verifier = self.authorize(scope)
        self.assertEqual(status, 302)
        code = parse_qs(urlsplit(headers["location"]).query)["code"][0]
        status, _, body = self.form(srv.TOKEN_PATH, {
            "grant_type": "authorization_code", "code": code, "redirect_uri": REDIRECT,
            "client_id": srv.DEFAULT_CLIENT_ID, "client_secret": srv.DEFAULT_CLIENT_SECRET, "code_verifier": verifier})
        self.assertEqual(status, 200, body)
        return json.loads(body)

    def refresh(self, refresh_token: str, secret: str | None = srv.DEFAULT_CLIENT_SECRET) -> tuple[int, dict, bytes]:
        values = {"grant_type": "refresh_token", "refresh_token": refresh_token, "client_id": srv.DEFAULT_CLIENT_ID}
        if secret is not None:
            values["client_secret"] = secret
        return self.form(srv.TOKEN_PATH, values)

    def drive(self, method: str, path: str, token: str | None, body=b"", headers: dict | None = None
              ) -> tuple[int, dict, bytes]:
        all_headers = dict(headers or {})
        if token is not None:
            all_headers["Authorization"] = f"Bearer {token}"
        return self.request(method, path, body, all_headers)

    def about(self, token: str) -> int:
        return self.drive("GET", srv.DRIVE_ABOUT_PATH + "?fields=user(emailAddress)", token)[0]

    def create(self, token: str, name: str, media: bytes = b"segment", parent: str = "appDataFolder") -> dict:
        body, headers = multipart({"name": name, "parents": [parent]}, media)
        status, _, answer = self.drive("POST", srv.UPLOAD_PATH + "?uploadType=multipart", token, body, headers)
        self.assertEqual(status, 200, answer)
        return json.loads(answer)

    def list_files(self, token: str, **query) -> tuple[int, dict]:
        query.setdefault("spaces", "appDataFolder")
        status, _, body = self.drive("GET", srv.DRIVE_FILES_PATH + "?" + urlencode(query), token)
        return status, json.loads(body)


class WebDavTest(FixtureTest):
    def test_every_request_needs_the_configured_basic_credentials(self):
        status, headers, _ = self.request("PROPFIND", "/dav/", headers={"Depth": "0"})
        self.assertEqual(status, 401)
        self.assertTrue(headers["www-authenticate"].startswith("Basic "))
        self.assertEqual(self.dav("PROPFIND", "", headers={"Depth": "0"}, password="not-it")[0], 401)
        self.assertEqual(self.dav("PUT", "f", b"x", password="not-it")[0], 401)
        self.assertEqual(self.dav("GET", "f")[0], 404)
        self.assertEqual(self.dav("PROPFIND", "", headers={"Depth": "0"})[0], 207)

    def test_the_methods_round_trip_with_an_etag_on_every_resource(self):
        self.assertEqual(self.dav("MKCOL", "saikou-sync")[0], 201)
        self.assertEqual(self.dav("MKCOL", "saikou-sync")[0], 405)
        self.assertEqual(self.dav("MKCOL", "no/parent")[0], 409)
        self.assertEqual(self.dav("PUT", "no/parent.gz", b"x")[0], 409)

        status, headers, _ = self.dav("PUT", "saikou-sync/0-5 seg.gz", b"first", {"Content-Type": "application/gzip"})
        self.assertEqual(status, 201)
        first_etag = headers["etag"]
        self.assertRegex(first_etag, r'^"[0-9a-f]{32}"$')
        status, headers, body = self.dav("GET", "saikou-sync/0-5 seg.gz")
        self.assertEqual((status, body, headers["etag"], headers["content-type"]),
                         (200, b"first", first_etag, "application/gzip"))
        status, headers, body = self.dav("HEAD", "saikou-sync/0-5 seg.gz")
        self.assertEqual((status, body, headers["content-length"]), (200, b"", "5"))

        collection_before = self.propfind_etag("saikou-sync", "0")
        status, headers, _ = self.dav("PUT", "saikou-sync/0-5 seg.gz", b"second")
        self.assertEqual(status, 204)
        self.assertNotEqual(headers["etag"], first_etag)
        self.assertNotEqual(self.propfind_etag("saikou-sync", "0"), collection_before)

        self.assertEqual(self.listing("saikou-sync", "0"), ["/dav/saikou-sync/"])
        self.assertEqual(self.listing("saikou-sync"), ["/dav/saikou-sync/", "/dav/saikou-sync/0-5%20seg.gz"])
        _, _, body = self.dav("PROPFIND", "saikou-sync", headers={"Depth": "1"})
        self.assertIn(f"<d:getetag>{headers['etag']}</d:getetag>", body.decode())
        self.assertIn("<d:getcontentlength>6</d:getcontentlength>", body.decode())
        self.assertIn("<d:collection/>", body.decode())

        self.assertEqual(self.dav("PROPFIND", "saikou-sync")[0], 403)
        self.assertEqual(self.dav("PROPFIND", "saikou-sync", headers={"Depth": "infinity"})[0], 403)
        self.assertEqual(self.dav("PROPFIND", "missing", headers={"Depth": "0"})[0], 404)
        self.assertEqual(self.dav("GET", "saikou-sync")[0], 405)

        self.assertEqual(self.dav("DELETE", "saikou-sync/0-5 seg.gz")[0], 204)
        self.assertEqual(self.dav("GET", "saikou-sync/0-5 seg.gz")[0], 404)
        self.assertEqual(self.dav("PUT", "saikou-sync/a", b"a")[0], 201)
        self.assertEqual(self.dav("DELETE", "saikou-sync")[0], 204)
        self.assertEqual(self.dav("GET", "saikou-sync/a")[0], 404)
        self.assertEqual(self.listing(), ["/dav/"])
        self.assertEqual(self.dav("DELETE", "saikou-sync")[0], 404)
        self.assertEqual(self.dav("GET", "a/../b")[0], 400)

    def propfind_etag(self, rel: str, depth: str) -> str:
        _, _, body = self.dav("PROPFIND", rel, headers={"Depth": depth})
        return body.decode().split("<d:getetag>")[1].split("</d:getetag>")[0]

    def test_a_chunked_put_is_stored_whole(self):
        status, _, _ = self.dav("PUT", "streamed.gz", iter([b"ab", b"cd", b"ef"]),
                                {"Transfer-Encoding": "chunked"}, chunked=True)
        self.assertEqual(status, 201)
        self.assertEqual(self.dav("GET", "streamed.gz")[2], b"abcdef")

    def test_put_and_delete_honour_if_match_and_if_none_match(self):
        etag = self.dav("PUT", "f", b"one")[1]["etag"]
        self.assertEqual(self.dav("PUT", "f", b"two", {"If-None-Match": "*"})[0], 412)
        self.assertEqual(self.dav("PUT", "f", b"two", {"If-Match": '"other"'})[0], 412)
        self.assertEqual(self.dav("DELETE", "f", headers={"If-Match": '"other"'})[0], 412)
        self.assertEqual(self.dav("PUT", "f", b"two", {"If-Match": etag})[0], 204)
        self.assertEqual(self.dav("PUT", "g", b"new", {"If-None-Match": "*"})[0], 201)
        self.assertEqual(self.dav("GET", "f")[2], b"two")

    def test_the_lying_mode_answers_a_put_with_success_and_writes_nothing(self):
        self.assertEqual(self.dav("PUT", "kept", b"kept")[0], 201)
        self.control("modes", {"lie": True})
        status, headers, _ = self.dav("PUT", "lost", b"lost bytes")
        self.assertEqual(status, 201)
        self.assertRegex(headers["etag"], r'^"[0-9a-f]{32}"$')
        self.assertEqual(self.dav("PUT", "kept", b"changed")[0], 204)
        self.assertEqual(self.dav("GET", "lost")[0], 404)
        self.assertEqual(self.dav("GET", "kept")[2], b"kept")
        self.assertEqual(self.listing(), ["/dav/", "/dav/kept"])
        self.assertEqual([r["injected"] for r in self.records("dav.put")], [None, "lie", "lie"])

        self.control("modes", {"lie": False})
        self.assertEqual(self.dav("PUT", "lost", b"lost bytes")[0], 201)
        self.assertEqual(self.dav("GET", "lost")[2], b"lost bytes")

    def test_the_hiding_mode_leaves_one_file_out_of_a_listing_and_still_serves_it(self):
        self.dav("MKCOL", "d")
        for name in ("d/0-5.gz", "d/5-9.gz", "d/9-12.gz"):
            self.assertEqual(self.dav("PUT", name, name.encode())[0], 201)
        self.control("modes", {"hide": "d/5-9.gz"})
        self.assertEqual(self.listing("d"), ["/dav/d/", "/dav/d/0-5.gz", "/dav/d/9-12.gz"])
        self.assertEqual(self.dav("GET", "d/5-9.gz")[2], b"d/5-9.gz")
        self.assertEqual(self.records("dav.propfind")[-1]["injected"], "hide")
        self.control("modes", {"hide": None})
        self.assertEqual(len(self.listing("d")), 4)


class OAuthTest(FixtureTest):
    def test_the_authorization_code_grant_with_pkce_needs_no_browser(self):
        status, headers, verifier = self.authorize()
        self.assertEqual(status, 302)
        location = urlsplit(headers["location"])
        self.assertEqual(f"{location.scheme}://{location.netloc}{location.path}", REDIRECT)
        answer = parse_qs(location.query)
        self.assertEqual((answer["state"], answer["scope"]), (["st-1"], [srv.DRIVE_APPDATA_SCOPE]))
        code = answer["code"][0]
        self.assertTrue(code.startswith("4/"))
        exchange = {"grant_type": "authorization_code", "code": code, "redirect_uri": REDIRECT,
                    "client_id": srv.DEFAULT_CLIENT_ID, "client_secret": srv.DEFAULT_CLIENT_SECRET,
                    "code_verifier": verifier}
        status, headers, body = self.form(srv.TOKEN_PATH, exchange)
        self.assertEqual(status, 200)
        self.assertTrue(headers["content-type"].startswith("application/json"))
        tokens = json.loads(body)
        self.assertEqual(sorted(tokens), ["access_token", "expires_in", "refresh_token", "scope", "token_type"])
        self.assertEqual((tokens["expires_in"], tokens["scope"], tokens["token_type"]),
                         (3599, srv.DRIVE_APPDATA_SCOPE, "Bearer"))
        self.assertEqual(self.about(tokens["access_token"]), 200)
        # A code is good once.
        status, _, body = self.form(srv.TOKEN_PATH, exchange)
        self.assertEqual((status, json.loads(body)["error"]), (400, "invalid_grant"))

    def test_the_authorization_endpoint_refuses_what_google_refuses_a_desktop_client(self):
        cases = [
            ({"client_id": "someone-else"}, 401, "invalid_client"),
            ({"redirect_uri": "https://sync.test/callback"}, 400, "redirect_uri_mismatch"),
            ({"redirect_uri": "http://10.0.2.2:18795/"}, 400, "redirect_uri_mismatch"),
            ({"response_type": "token"}, 400, "unsupported_response_type"),
            ({"code_challenge_method": "plain"}, 400, "invalid_request"),
            ({"code_challenge": "short"}, 400, "invalid_request"),
            ({"scope": ""}, 400, "invalid_request"),
        ]
        for override, status, error in cases:
            with self.subTest(override=override):
                got, headers, _ = self.authorize(**override)
                self.assertEqual(got, status)
                self.assertNotIn("location", headers)
        self.assertEqual(self.authorize(redirect_uri="http://localhost:5000/cb")[0], 302)

    def test_the_delay_mode_holds_the_authorization_answer_and_serves_everything_else_meanwhile(self):
        # Unit O's spike: the approval arrives authorizeDelay seconds after the browser opened.
        self.control("modes", {"authorizeDelay": 1})
        answers = []
        started = time.monotonic()
        waiting = threading.Thread(target=lambda: answers.append(self.authorize()), daemon=True)
        waiting.start()
        time.sleep(0.2)
        self.assertEqual(self.control("state")["modes"]["authorizeDelay"], 1)  # not held by the wait
        self.assertLess(time.monotonic() - started, 0.9)
        waiting.join(5)
        self.assertGreaterEqual(time.monotonic() - started, 1.0)
        status, headers, verifier = answers[0]
        self.assertEqual(status, 302)
        # The code is issued when the wait ends, and exchanges like any other.
        code = parse_qs(urlsplit(headers["location"]).query)["code"][0]
        status, _, body = self.form(srv.TOKEN_PATH, {
            "grant_type": "authorization_code", "code": code, "redirect_uri": REDIRECT,
            "client_id": srv.DEFAULT_CLIENT_ID, "client_secret": srv.DEFAULT_CLIENT_SECRET, "code_verifier": verifier})
        self.assertEqual(status, 200, body)
        self.control("modes", {"authorizeDelay": None})
        self.assertEqual(self.authorize()[0], 302)
        held, prompt = self.records("oauth.authorize")
        self.assertEqual((held["injected"], held["delay_s"], held["status"]), ("delay", 1, 302))
        self.assertGreaterEqual(held["t1"] - held["t0"], 1.0)
        self.assertIsNone(prompt["injected"])
        self.assertNotIn("delay_s", prompt)
        self.assertLess(prompt["t1"] - prompt["t0"], 0.5)

    def test_the_code_exchange_checks_the_verifier_and_the_redirect(self):
        for field_name, value, error in (("code_verifier", "x" * 43, "invalid_grant"),
                                         ("redirect_uri", "http://127.0.0.1:1/", "redirect_uri_mismatch")):
            with self.subTest(field=field_name):
                _, headers, verifier = self.authorize()
                values = {"grant_type": "authorization_code", "redirect_uri": REDIRECT,
                          "code": parse_qs(urlsplit(headers["location"]).query)["code"][0],
                          "client_id": srv.DEFAULT_CLIENT_ID, "client_secret": srv.DEFAULT_CLIENT_SECRET,
                          "code_verifier": verifier, field_name: value}
                status, _, body = self.form(srv.TOKEN_PATH, values)
                self.assertEqual((status, json.loads(body)["error"]), (400, error))

    def test_the_client_secret_is_required_on_the_exchange_and_on_every_refresh(self):
        for secret, status, error in ((None, 400, "invalid_request"), ("wrong", 401, "invalid_client")):
            with self.subTest(secret=secret):
                _, headers, verifier = self.authorize()
                values = {"grant_type": "authorization_code", "redirect_uri": REDIRECT,
                          "code": parse_qs(urlsplit(headers["location"]).query)["code"][0],
                          "client_id": srv.DEFAULT_CLIENT_ID, "code_verifier": verifier}
                if secret:
                    values["client_secret"] = secret
                got, _, body = self.form(srv.TOKEN_PATH, values)
                self.assertEqual((got, json.loads(body)["error"]), (status, error))
        refresh_token = self.sign_in()["refresh_token"]
        got, _, body = self.refresh(refresh_token, secret=None)
        self.assertEqual((got, json.loads(body)), (400, {"error": "invalid_request",
                                                         "error_description": "client_secret is missing."}))
        got, _, body = self.refresh(refresh_token, secret="wrong")
        self.assertEqual((got, json.loads(body)["error"]), (401, "invalid_client"))
        got, _, body = self.refresh(refresh_token, secret="")
        self.assertEqual((got, json.loads(body)["error"]), (400, "invalid_request"))
        got, _, body = self.refresh(refresh_token)
        self.assertEqual(got, 200)
        self.assertEqual(sorted(json.loads(body)), ["access_token", "expires_in", "scope", "token_type"])
        self.assertEqual([r["client_secret"] for r in self.records("oauth.token")],
                         ["missing", "wrong", "present", "missing", "wrong", "missing", "present"])
        self.assertEqual([r["grant"] for r in self.records("oauth.token")][-4:], ["refresh_token"] * 4)

    def test_access_tokens_expire_and_a_refresh_replaces_them(self):
        tokens = self.sign_in()
        self.clock.advance(3598)
        self.assertEqual(self.about(tokens["access_token"]), 200)
        self.clock.advance(1)
        status, headers, body = self.drive("GET", srv.DRIVE_ABOUT_PATH + "?fields=user", tokens["access_token"])
        self.assertEqual(status, 401)
        self.assertIn('error="invalid_token"', headers["www-authenticate"])
        self.assertEqual(json.loads(body)["error"]["errors"][0]["reason"], "authError")
        status, _, body = self.refresh(tokens["refresh_token"])
        fresh = json.loads(body)["access_token"]
        self.assertEqual(self.about(fresh), 200)
        self.control("expire-access-tokens")
        self.assertEqual(self.about(fresh), 401)
        self.control("modes", {"accessTokenLifetime": 60})
        self.assertEqual(json.loads(self.refresh(tokens["refresh_token"])[2])["expires_in"], 60)

    def test_the_refresh_fails_with_a_dropped_connection_then_503_then_succeeds(self):
        tokens = self.sign_in()
        self.control("modes", {"refreshFailures": ["drop", "503"]})
        with self.assertRaises((http.client.RemoteDisconnected, ConnectionError)):
            self.refresh(tokens["refresh_token"])
        status, _, _ = self.refresh(tokens["refresh_token"])
        self.assertEqual(status, 503)
        status, _, body = self.refresh(tokens["refresh_token"])
        self.assertEqual(status, 200)
        self.assertNotIn("refresh_token", json.loads(body))
        self.assertEqual(self.control("state")["modes"]["refreshFailures"], [])
        refreshes = [r for r in self.records("oauth.token") if r["grant"] == "refresh_token"]
        self.assertEqual([(r["status"], r["injected"]) for r in refreshes], [(None, "drop"), (503, "503"), (200, None)])
        self.assertEqual({r["client_secret"] for r in refreshes}, {"present"})

    def test_one_drive_call_is_answered_401_before_the_stored_expiry(self):
        token = self.sign_in()["access_token"]
        self.control("modes", {"early401": 1})
        self.assertEqual(self.about(token), 401)
        self.assertEqual(self.about(token), 200)
        self.assertEqual([r["injected"] for r in self.records("drive.about.get")], ["early401", None])

    def test_the_revoked_mode_answers_invalid_grant_until_it_is_turned_off(self):
        tokens = self.sign_in()
        self.control("modes", {"revoked": True})
        status, _, body = self.refresh(tokens["refresh_token"])
        self.assertEqual((status, json.loads(body)["error"]), (400, "invalid_grant"))
        self.assertEqual(self.about(tokens["access_token"]), 401)
        self.control("modes", {"revoked": False})
        self.assertEqual(self.refresh(tokens["refresh_token"])[0], 200)
        self.assertEqual(self.about(tokens["access_token"]), 200)

    def test_the_revoke_endpoint_ends_the_grant_of_either_token(self):
        for which in ("refresh_token", "access_token"):
            with self.subTest(which=which):
                tokens = self.sign_in()
                status, _, body = self.form(srv.REVOKE_PATH, {"token": tokens[which]})
                self.assertEqual((status, body), (200, b""))
                self.assertEqual(json.loads(self.refresh(tokens["refresh_token"])[2])["error"], "invalid_grant")
                self.assertEqual(self.about(tokens["access_token"]), 401)
                status, _, body = self.form(srv.REVOKE_PATH, {"token": tokens[which]})
                self.assertEqual((status, json.loads(body)["error"]), (400, "invalid_token"))
        tokens = self.sign_in()
        status, _, _ = self.request("POST", srv.REVOKE_PATH + "?" + urlencode({"token": tokens["refresh_token"]}))
        self.assertEqual(status, 200)

    def test_refresh_token_expires_in_is_sent_and_kept(self):
        plain = self.sign_in()
        self.control("modes", {"refreshTokenExpiresIn": 7 * DAY_S})
        limited = self.sign_in()
        self.assertEqual(limited["refresh_token_expires_in"], 7 * DAY_S)
        self.clock.advance(DAY_S)
        body = json.loads(self.refresh(limited["refresh_token"])[2])
        self.assertEqual(body["refresh_token_expires_in"], 6 * DAY_S)
        self.clock.advance(6 * DAY_S)
        self.assertEqual(json.loads(self.refresh(limited["refresh_token"])[2])["error"], "invalid_grant")
        self.assertNotIn("refresh_token_expires_in", json.loads(self.refresh(plain["refresh_token"])[2]))

    def test_a_token_without_the_appdata_scope_is_refused_by_drive(self):
        token = self.sign_in(scope="openid email")["access_token"]
        status, _, body = self.drive("GET", srv.DRIVE_ABOUT_PATH + "?fields=user", token)
        self.assertEqual((status, json.loads(body)["error"]["code"]), (403, 403))

    def test_the_fourteen_day_scenario_reads_from_the_log_as_the_row_reads_it(self):
        tokens = self.sign_in()
        start = len(self.records())
        self.clock.advance(14 * DAY_S)
        self.control("expire-access-tokens")
        self.control("modes", {"refreshFailures": ["drop", "503"], "early401": 1})
        self.assertEqual(self.about(tokens["access_token"]), 401)
        with self.assertRaises((http.client.RemoteDisconnected, ConnectionError)):
            self.refresh(tokens["refresh_token"])
        self.assertEqual(self.refresh(tokens["refresh_token"])[0], 503)
        access = json.loads(self.refresh(tokens["refresh_token"])[2])["access_token"]
        self.assertEqual(self.about(access), 401)  # the early 401
        access = json.loads(self.refresh(tokens["refresh_token"])[2])["access_token"]
        self.assertEqual(self.about(access), 200)
        after = self.records()[start:]
        self.assertFalse([r for r in after if r["kind"] == "oauth.authorize"])
        grants = [r for r in after if r["kind"] == "oauth.token"]
        self.assertTrue(grants and all(r["grant"] == "refresh_token" and r["client_secret"] == "present"
                                       for r in grants))
        self.assertEqual(sum(1 for r in grants if r["status"] == 200), 2)


class DriveTest(FixtureTest):
    def setUp(self):
        super().setUp()
        self.token = self.sign_in()["access_token"]

    def test_every_call_needs_a_bearer_token(self):
        for method, path in (("GET", srv.DRIVE_FILES_PATH + "?spaces=appDataFolder"), ("GET", srv.DRIVE_ABOUT_PATH),
                             ("POST", srv.UPLOAD_PATH + "?uploadType=multipart"), ("GET", srv.DRIVE_FILES_PATH + "/x"),
                             ("DELETE", srv.DRIVE_FILES_PATH + "/x"), ("POST", srv.DRIVE_FILES_PATH)):
            with self.subTest(method=method, path=path):
                self.assertEqual(self.drive(method, path, None)[0], 401)
                self.assertEqual(self.drive(method, path, "ya29.not-issued")[0], 401)

    def test_about_get_requires_fields_and_names_the_account(self):
        status, _, body = self.drive("GET", srv.DRIVE_ABOUT_PATH, self.token)
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(body)["error"]["errors"][0]["location"], "fields")
        status, _, body = self.drive("GET", srv.DRIVE_ABOUT_PATH + "?fields=user(emailAddress)", self.token)
        self.assertEqual((status, json.loads(body)), (200, {"user": {"emailAddress": srv.DEFAULT_ACCOUNT_EMAIL}}))
        status, _, body = self.drive("GET", srv.DRIVE_ABOUT_PATH + "?fields=user(nickname)", self.token)
        self.assertEqual(status, 400)

    def test_a_multipart_create_is_read_back_and_deleted(self):
        media = b"\x1f\x8b gzip bytes"
        created = self.create(self.token, "devices/a/0-5.jsonl.gz", media)
        self.assertEqual(sorted(created), ["id", "kind", "mimeType", "name"])
        self.assertEqual((created["kind"], created["name"], created["mimeType"]),
                         ("drive#file", "devices/a/0-5.jsonl.gz", "application/gzip"))
        status, _, body = self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{created['id']}?fields=*", self.token)
        full = json.loads(body)
        self.assertEqual(status, 200)
        self.assertEqual((full["size"], full["md5Checksum"], full["spaces"], full["trashed"]),
                         (str(len(media)), hashlib.md5(media).hexdigest(), ["appDataFolder"], False))
        self.assertIsInstance(full["version"], str)
        self.assertRegex(full["createdTime"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$")
        self.assertEqual(len(full["parents"]), 1)
        self.assertNotEqual(full["parents"][0], "appDataFolder")
        status, headers, body = self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{created['id']}?alt=media", self.token)
        self.assertEqual((status, body, headers["content-type"]), (200, media, "application/gzip"))
        status, _, body = self.drive("DELETE", f"{srv.DRIVE_FILES_PATH}/{created['id']}", self.token)
        self.assertEqual((status, body), (204, b""))
        status, _, body = self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{created['id']}", self.token)
        error = json.loads(body)["error"]
        self.assertEqual((status, error["code"], error["errors"][0]["reason"]), (404, 404, "notFound"))
        self.assertEqual(self.drive("DELETE", f"{srv.DRIVE_FILES_PATH}/{created['id']}", self.token)[0], 404)

    def test_a_create_outside_the_appdata_folder_is_refused(self):
        body, headers = multipart({"name": "loose"}, b"x")
        status, _, answer = self.drive("POST", srv.UPLOAD_PATH + "?uploadType=multipart", self.token, body, headers)
        self.assertEqual((status, json.loads(answer)["error"]["errors"][0]["reason"]),
                         (403, "insufficientFilePermissions"))
        body, headers = multipart({"name": "x", "parents": ["no-such-folder"]}, b"x")
        status, _, _ = self.drive("POST", srv.UPLOAD_PATH + "?uploadType=multipart", self.token, body, headers)
        self.assertEqual(status, 404)
        self.assertEqual(self.drive("POST", srv.UPLOAD_PATH + "?uploadType=media", self.token, b"x")[0], 400)

    def test_files_list_serves_only_the_appdata_space(self):
        for spaces in ("drive", "drive,appDataFolder"):
            self.assertEqual(self.list_files(self.token, spaces=spaces)[0], 403)
        status, _, _ = self.drive("GET", srv.DRIVE_FILES_PATH, self.token)
        self.assertEqual(status, 403)
        status, body = self.list_files(self.token)
        self.assertEqual((status, body), (200, {"kind": "drive#fileList", "incompleteSearch": False, "files": []}))

    def test_files_list_pages_with_next_page_token(self):
        names = [f"s{n}" for n in range(5)]
        for name in names:
            self.clock.advance(1)
            self.create(self.token, name)
        seen, token, pages = [], None, 0
        # Bounded, so a token that never moves fails here rather than hanging the suite.
        for pages in range(1, 11):
            query = {"pageSize": 2, **({"pageToken": token} if token else {})}
            status, body = self.list_files(self.token, **query)
            self.assertEqual(status, 200)
            seen += [f["name"] for f in body["files"]]
            token = body.get("nextPageToken")
            if not token:
                break
        self.assertEqual((seen, pages), (names, 3))
        self.assertEqual(len(self.list_files(self.token)[1]["files"]), 5)

        self.control("modes", {"drivePageLimit": 3})
        status, body = self.list_files(self.token, pageSize=1000, fields="nextPageToken,files(name)")
        self.assertEqual(body["files"], [{"name": "s0"}, {"name": "s1"}, {"name": "s2"}])
        self.assertEqual(sorted(body), ["files", "nextPageToken"])
        rest = self.list_files(self.token, pageToken=body["nextPageToken"], fields="files/name")[1]
        self.assertEqual(rest, {"files": [{"name": "s3"}, {"name": "s4"}]})

        for bad in ({"pageSize": 0}, {"pageSize": 1001}, {"pageToken": "not-a-token"}, {"fields": "files(owner)"},
                    {"orderBy": "size"}):
            with self.subTest(bad=bad):
                self.assertEqual(self.list_files(self.token, **bad)[0], 400)

    def test_files_list_filters_and_orders(self):
        status, _, body = self.drive("POST", srv.DRIVE_FILES_PATH, self.token,
                                     json.dumps({"name": "devices", "mimeType": srv.FOLDER_MIME,
                                                 "parents": ["appDataFolder"]}).encode(),
                                     {"Content-Type": "application/json; charset=UTF-8"})
        self.assertEqual(status, 200)
        folder = json.loads(body)["id"]
        for name in ("snapshot-2-b", "snapshot-1-a", "it's"):
            self.clock.advance(1)
            self.create(self.token, name)
        self.create(self.token, "0-5.jsonl.gz", parent=folder)

        def names(q: str, **query) -> list[str]:
            status, body = self.list_files(self.token, q=q, **query)
            self.assertEqual(status, 200, body)
            return [f["name"] for f in body["files"]]

        self.assertEqual(names("name contains 'snapshot-'", orderBy="name"), ["snapshot-1-a", "snapshot-2-b"])
        self.assertEqual(names("name contains 'shot'"), [])
        self.assertEqual(names("name = 'it\\'s' and trashed = false"), ["it's"])
        self.assertEqual(names(f"'{folder}' in parents"), ["0-5.jsonl.gz"])
        self.assertEqual(names("'appDataFolder' in parents and mimeType != 'application/vnd.google-apps.folder'",
                               orderBy="createdTime desc"), ["it's", "snapshot-1-a", "snapshot-2-b"])
        self.assertEqual(names("trashed = true"), [])
        self.assertEqual(self.list_files(self.token, q="fullText contains 'x'")[0], 400)

        self.assertEqual(self.drive("DELETE", f"{srv.DRIVE_FILES_PATH}/{folder}", self.token)[0], 204)
        self.assertEqual(names("name = '0-5.jsonl.gz'"), [])
        self.assertEqual(self.drive("DELETE", f"{srv.DRIVE_FILES_PATH}/appDataFolder", self.token)[0], 403)

    def test_a_resumable_upload_resumes_from_the_range_the_308_names(self):
        media = os.urandom(2 * srv.RESUMABLE_CHUNK + 1000)
        total = len(media)
        status, headers, body = self.drive(
            "POST", srv.UPLOAD_PATH + "?uploadType=resumable&fields=id,name,size", self.token,
            json.dumps({"name": "snapshot-3-a.jsonl.gz", "parents": ["appDataFolder"]}).encode(),
            {"Content-Type": "application/json; charset=UTF-8", "X-Upload-Content-Type": "application/gzip",
             "X-Upload-Content-Length": str(total)})
        self.assertEqual((status, body), (200, b""))
        session = urlsplit(headers["location"])
        self.assertEqual(session.netloc, f"127.0.0.1:{self.port}")
        session_path = f"{session.path}?{session.query}"

        def put(data: bytes, content_range: str) -> tuple[int, dict, bytes]:
            return self.request("PUT", session_path, data, {"Content-Range": content_range})

        # A chunk that is not a multiple of 256 KiB keeps only the whole chunks in it.
        first = media[:srv.RESUMABLE_CHUNK + 500]
        status, headers, _ = put(first, f"bytes 0-{len(first) - 1}/{total}")
        self.assertEqual((status, headers.get("range"), headers.get("location")),
                         (308, f"bytes=0-{srv.RESUMABLE_CHUNK - 1}", None))
        status, headers, _ = put(b"", f"bytes */{total}")
        self.assertEqual((status, headers["range"]), (308, f"bytes=0-{srv.RESUMABLE_CHUNK - 1}"))
        # A chunk from the wrong offset stores nothing, even a whole one sent again.
        for start, end in ((5, 9), (0, srv.RESUMABLE_CHUNK - 1)):
            status, headers, _ = put(media[start:end + 1], f"bytes {start}-{end}/{total}")
            self.assertEqual((status, headers["range"]), (308, f"bytes=0-{srv.RESUMABLE_CHUNK - 1}"))
        second = media[srv.RESUMABLE_CHUNK:2 * srv.RESUMABLE_CHUNK]
        status, headers, _ = put(second, f"bytes {srv.RESUMABLE_CHUNK}-{2 * srv.RESUMABLE_CHUNK - 1}/*")
        self.assertEqual((status, headers["range"]), (308, f"bytes=0-{2 * srv.RESUMABLE_CHUNK - 1}"))
        status, _, body = put(media[2 * srv.RESUMABLE_CHUNK:], f"bytes {2 * srv.RESUMABLE_CHUNK}-{total - 1}/{total}")
        self.assertEqual(status, 200)
        created = json.loads(body)
        self.assertEqual(sorted(created), ["id", "name", "size"])
        self.assertEqual(created["size"], str(total))
        # A finished session answers its file again.
        self.assertEqual(json.loads(put(b"", f"bytes */{total}")[2]), created)
        got = self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{created['id']}?alt=media", self.token)[2]
        self.assertEqual(hashlib.sha256(got).hexdigest(), hashlib.sha256(media).hexdigest())
        self.assertEqual(self.request("PUT", srv.UPLOAD_PATH + "?uploadType=resumable&upload_id=gone", b"x")[0], 404)
        chunks = self.records("drive.upload.chunk")
        self.assertEqual([r["status"] for r in chunks], [308, 308, 308, 308, 308, 200, 200, 404])

    def test_a_resumable_upload_in_one_request_and_an_empty_one(self):
        for media in (b"whole file", b""):
            with self.subTest(size=len(media)):
                status, headers, _ = self.drive(
                    "POST", srv.UPLOAD_PATH + "?uploadType=resumable", self.token,
                    json.dumps({"name": f"f{len(media)}", "parents": ["appDataFolder"]}).encode(),
                    {"Content-Type": "application/json; charset=UTF-8"})
                session = urlsplit(headers["location"])
                path = f"{session.path}?{session.query}"
                if media:
                    status, _, body = self.request("PUT", path, media)
                else:
                    status, _, body = self.request("PUT", path, b"", {"Content-Range": "bytes */0"})
                self.assertEqual(status, 200)
                file_id = json.loads(body)["id"]
                self.assertEqual(self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{file_id}?alt=media", self.token)[2],
                                 media)

    def test_a_session_put_checks_a_token_it_carries_and_needs_none(self):
        # Google's upload guide sends a session PUT with no Authorization header, so the address alone
        # is served; a client that sends its token on every chunk gets it checked, so an upload that
        # outlives its token, or meets the early 401, is refused mid-way and has to refresh and resume.
        media = os.urandom(2 * srv.RESUMABLE_CHUNK)
        total = len(media)
        status, headers, _ = self.drive(
            "POST", srv.UPLOAD_PATH + "?uploadType=resumable", self.token,
            json.dumps({"name": "snapshot-4-a.jsonl.gz", "parents": ["appDataFolder"]}).encode(),
            {"Content-Type": "application/json; charset=UTF-8", "X-Upload-Content-Length": str(total)})
        self.assertEqual(status, 200)
        session = urlsplit(headers["location"])
        session_path = f"{session.path}?{session.query}"
        first, second = media[:srv.RESUMABLE_CHUNK], media[srv.RESUMABLE_CHUNK:]
        first_range = f"bytes 0-{srv.RESUMABLE_CHUNK - 1}/{total}"

        def put(data: bytes, content_range: str, token: str | None) -> tuple[int, dict, bytes]:
            return self.drive("PUT", session_path, token, data, {"Content-Range": content_range})

        self.control("expire-access-tokens")
        status, headers, body = put(first, first_range, self.token)
        self.assertEqual((status, json.loads(body)["error"]["errors"][0]["reason"]), (401, "authError"))
        self.assertIn("www-authenticate", headers)
        self.assertEqual(put(first, first_range, "never-issued")[0], 401)
        # Nothing was kept: the status query, with no token, names no Range.
        status, headers, _ = put(b"", f"bytes */{total}", None)
        self.assertEqual((status, headers.get("range")), (308, None))

        self.token = self.sign_in()["access_token"]
        self.control("modes", {"early401": 1})
        self.assertEqual(put(first, first_range, self.token)[0], 401)
        status, headers, _ = put(first, first_range, self.token)
        self.assertEqual((status, headers["range"]), (308, f"bytes=0-{srv.RESUMABLE_CHUNK - 1}"))
        status, _, body = put(second, f"bytes {srv.RESUMABLE_CHUNK}-{total - 1}/{total}", None)
        self.assertEqual(status, 200)
        got = self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{json.loads(body)['id']}?alt=media", self.token)[2]
        self.assertEqual(got, media)
        chunks = self.records("drive.upload.chunk")
        self.assertEqual([(r["status"], r["injected"]) for r in chunks],
                         [(401, None), (401, None), (308, None), (401, "early401"), (308, None), (200, None)])

    def test_the_lying_mode_answers_a_create_with_a_file_that_is_not_there(self):
        self.control("modes", {"lie": True})
        created = self.create(self.token, "lost")
        self.assertEqual(self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{created['id']}", self.token)[0], 404)
        self.assertEqual(self.list_files(self.token)[1]["files"], [])
        self.assertEqual(self.records("drive.files.create")[-1]["injected"], "lie")
        self.control("modes", {"lie": False})
        kept = self.create(self.token, "kept")
        self.assertEqual(self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{kept['id']}", self.token)[0], 200)

    def test_the_hiding_mode_leaves_one_file_out_of_files_list(self):
        for name in ("0-5.gz", "5-9.gz", "9-12.gz"):
            self.clock.advance(1)
            self.create(self.token, name)
        self.control("modes", {"hide": "5-9.gz"})
        status, body = self.list_files(self.token, fields="files(id,name)")
        self.assertEqual([f["name"] for f in body["files"]], ["0-5.gz", "9-12.gz"])
        hidden = self.list_files(self.token, q="name = '5-9.gz'")[1]
        self.assertEqual(hidden["files"], [])
        self.assertEqual(self.records("drive.files.list")[-1]["injected"], "hide")
        self.control("modes", {"hide": None})
        file_id = self.list_files(self.token, q="name = '5-9.gz'")[1]["files"][0]["id"]
        self.assertEqual(self.drive("GET", f"{srv.DRIVE_FILES_PATH}/{file_id}?alt=media", self.token)[2], b"segment")


class PathsAndControlTest(FixtureTest):
    def test_an_unknown_path_is_404_whatever_the_method(self):
        cases = [("GET", "/"), ("GET", "/probe"), ("MOVE", "/nothing"), ("POST", "/drive/v3/changes"),
                 ("GET", "/drive/v3/files/a/b"), ("GET", "/drive/v2/files"), ("PATCH", "/token/x"),
                 ("GET", "/davx"), ("GET", srv.CONTROL_PREFIX + "nothing"), ("LOCK", "/upload/drive/v3/other")]
        for method, path in cases:
            with self.subTest(method=method, path=path):
                self.assertEqual(self.request(method, path)[0], 404)
        self.assertEqual([r["status"] for r in self.records()], [404] * len(cases))
        self.assertEqual(self.records("unknown")[0]["path"], "/")

    def test_a_known_path_with_another_method_is_405(self):
        for method, path in (("GET", srv.TOKEN_PATH), ("POST", srv.AUTH_PATH), ("GET", srv.REVOKE_PATH),
                             ("PUT", srv.DRIVE_FILES_PATH), ("POST", srv.CONTROL_PREFIX + "state"),
                             ("GET", srv.CONTROL_PREFIX + "reset")):
            with self.subTest(method=method, path=path):
                self.assertEqual(self.request(method, path)[0], 405)
        self.assertEqual(self.dav("MOVE", "x")[0], 405)

    def test_a_mode_change_is_checked_whole_before_any_of_it_applies(self):
        before = self.control("state")["modes"]
        for body in ({"lie": True, "nothing": 1}, {"lie": True, "refreshFailures": ["drop", "timeout"]},
                     {"early401": -1}, {"early401": True}, {"accessTokenLifetime": 0}, {"hide": ""},
                     {"authorizeDelay": 0}, {"authorizeDelay": 1.5}, [1]):
            with self.subTest(body=body):
                status, _, _ = self.request("POST", srv.CONTROL_PREFIX + "modes", json.dumps(body).encode())
                self.assertEqual(status, 400)
        self.assertEqual(self.request("POST", srv.CONTROL_PREFIX + "modes", b"{")[0], 400)
        self.assertEqual(self.control("state")["modes"], before)
        self.assertEqual(before, {"lie": False, "hide": None, "revoked": False, "refreshFailures": [], "early401": 0,
                                  "accessTokenLifetime": 3599, "refreshTokenExpiresIn": None,
                                  "drivePageLimit": None, "authorizeDelay": None})

    def test_reset_empties_every_store_and_every_mode(self):
        tokens = self.sign_in()
        self.create(tokens["access_token"], "f")
        self.dav("PUT", "f", b"x")
        self.control("modes", {"lie": True, "early401": 2})
        state = self.control("reset")
        self.assertEqual({k: state[k] for k in ("davResources", "driveFiles", "uploads", "grants", "accessTokens")},
                         {"davResources": 0, "driveFiles": 0, "uploads": 0, "grants": 0, "accessTokens": 0})
        self.assertEqual((state["modes"]["lie"], state["modes"]["early401"]), (False, 0))
        self.assertEqual(self.about(tokens["access_token"]), 401)
        self.assertEqual(self.dav("GET", "f")[0], 404)
        self.assertEqual(sorted(p.name for p in Path(state["stateDir"]).iterdir()), ["dav", "drive", "uploads"])
        self.assertEqual([list(Path(state["stateDir"], d).iterdir()) for d in ("dav", "drive", "uploads")],
                         [[], [], []])

    def test_the_log_holds_one_line_per_request_and_no_secret(self):
        tokens = self.sign_in()
        self.refresh(tokens["refresh_token"])
        self.form(srv.REVOKE_PATH, {"token": tokens["refresh_token"]})
        self.dav("PUT", "f", b"x")
        self.records()
        text = self.log_path.read_text()
        self.assertEqual(len(text.splitlines()), 5)
        for secret in (tokens["access_token"], tokens["refresh_token"], srv.DEFAULT_CLIENT_SECRET,
                       srv.DEFAULT_DAV_PASSWORD, base64.b64encode(b"saikou:").decode()[:8]):
            self.assertNotIn(secret, text)
        self.assertEqual([r["kind"] for r in self.records()],
                         ["oauth.authorize", "oauth.token", "oauth.token", "oauth.revoke", "dav.put"])
        for record in self.records():
            self.assertLessEqual(record["t0"], record["t1"])
            self.assertFalse(record["aborted"])


def dav_authorization() -> dict:
    credentials = f"{srv.DEFAULT_DAV_USER}:{srv.DEFAULT_DAV_PASSWORD}".encode()
    return {"Authorization": "Basic " + base64.b64encode(credentials).decode()}


def begin_put(connection: http.client.HTTPConnection, rel: str, body: bytes, sent: int) -> None:
    """Sends a WebDAV PUT whose body stops after its first `sent` bytes; the rest is the caller's to send."""
    connection.putrequest("PUT", srv.DAV_PREFIX + rel)
    for name, value in {**dav_authorization(), "Content-Length": str(len(body))}.items():
        connection.putheader(name, value)
    connection.endheaders()
    connection.send(body[:sent])


class LatencyTest(FixtureTest):
    def test_the_default_latency_is_zero_and_holds_nothing(self):
        self.assertEqual(srv.Fixture(Path(self.tmp.name, "other")).latency_ms, 0)
        self.assertEqual(self.fixture.latency_ms, 0)
        self.assertEqual(self.dav("PUT", "plain", b"p")[0], 201)
        self.assertNotIn("latency_ms", self.records("dav.put")[0])

    def test_two_parallel_requests_at_200_ms_take_well_under_400_ms(self):
        self.fixture.latency_ms = 200
        ready = threading.Barrier(3)
        statuses: list[int] = []

        def put(name: str) -> None:
            ready.wait()
            statuses.append(self.dav("PUT", name, b"x")[0])

        threads = [threading.Thread(target=put, args=(name,), daemon=True) for name in ("one", "two")]
        for thread in threads:
            thread.start()
        ready.wait()
        started = time.monotonic()
        for thread in threads:
            thread.join(5)
        elapsed = time.monotonic() - started
        self.assertEqual(statuses, [201, 201])
        # Each was held its 200 ms; held one after the other they would take 400 ms at least.
        self.assertGreaterEqual(elapsed, 0.2)
        self.assertLess(elapsed, 0.35)
        held = self.records("dav.put")
        self.assertEqual([record["latency_ms"] for record in held], [200, 200])
        self.assertTrue(all(record["t1"] - record["t0"] >= 0.2 for record in held))
        # A control request is never held.
        began = time.monotonic()
        self.control("state")
        self.assertLess(time.monotonic() - began, 0.2)
        self.assertNotIn("latency_ms", self.records("control")[-1])


class StopTest(FixtureTest):
    """A stop as main makes one: shutdown, then server_close, and only then the log and the state go."""

    def stop_in_background(self) -> threading.Thread:
        def stop():
            self.server.shutdown()
            self.server.server_close()
        stopping = threading.Thread(target=stop, daemon=True)
        stopping.start()
        return stopping

    def wait_for_connections(self, busy: list[bool]) -> None:
        deadline = time.monotonic() + 5
        while True:
            with self.server._connections_changed:
                if list(self.server._connections.values()) == busy:
                    return
            self.assertLess(time.monotonic(), deadline, f"the connections never read {busy}")
            time.sleep(0.01)

    def test_a_stop_waits_for_the_request_being_answered(self):
        body = b"0123456789"
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        self.addCleanup(connection.close)
        begin_put(connection, "slow", body, 5)
        self.wait_for_connections([True])  # its request line is read; its body is not all there
        stopping = self.stop_in_background()
        stopping.join(0.5)
        self.assertTrue(stopping.is_alive(), "the stop returned while a request was still being answered")
        connection.send(body[5:])
        self.assertEqual(connection.getresponse().status, 201)
        # Woken by the answer, well before the grace (5 seconds from the stop) is over.
        stopping.join(2)
        self.assertFalse(stopping.is_alive())
        # Everything the request did is there the moment the stop returns, before main removes any of it.
        lines = [json.loads(line) for line in self.log_path.read_text().splitlines()]
        self.assertEqual([(r["kind"], r["path"], r["status"]) for r in lines], [("dav.put", "/dav/slow", 201)])
        self.assertEqual(Path(self.tmp.name, "state", "dav", "slow").read_bytes(), body)
        self.assertEqual(connection.sock.recv(1), b"")  # and then its connection closed

    def test_a_stop_ends_an_authorization_held_by_the_delay_mode(self):
        self.control("modes", {"authorizeDelay": 60})
        # The mode's own connection is still marked busy for a moment after its answer is read, and
        # the wait below would take it for the authorization's; the stop then closed the listening
        # socket with the authorization still waiting to be accepted, and its client read a reset (3
        # to 12 runs in 300 under load, 2026-09-26). So it is gone before the authorization starts.
        self.wait_for_connections([])
        answers = []
        waiting = threading.Thread(target=lambda: answers.append(self.authorize()), daemon=True)
        waiting.start()
        self.wait_for_connections([True])
        started = time.monotonic()
        self.stop_in_background().join(3)
        waiting.join(3)
        # Well inside the grace (5 seconds), so the wait never holds a stop.
        self.assertLess(time.monotonic() - started, 2)
        self.assertEqual(answers[0][0], 503)
        lines = [json.loads(line) for line in self.log_path.read_text().splitlines()]
        held = [r for r in lines if r["kind"] == "oauth.authorize"]
        self.assertEqual([(r["status"], r["injected"], r["aborted"]) for r in held], [(503, "delay", False)])

    def test_a_stop_closes_a_connection_waiting_for_its_next_request(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        self.addCleanup(connection.close)
        connection.request("GET", srv.CONTROL_PREFIX + "state")
        response = connection.getresponse()
        response.read()
        self.assertEqual((response.status, response.will_close), (200, False))
        self.wait_for_connections([False])  # answered and logged; its handler waits for a next request
        stopping = self.stop_in_background()
        stopping.join(5)
        self.assertFalse(stopping.is_alive(), "the stop waited on a connection with no request in it")
        self.assertEqual(connection.sock.recv(1), b"")

    def stop_past_the_grace(self, busy: list[bool]) -> tuple[float, str]:
        """Stops once the connections read `busy`, with a half second grace: its duration and stderr."""
        self.server.stop_grace_s = 0.5
        self.wait_for_connections(busy)
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            began = time.monotonic()
            # The log's lock, held: once its connection is closed the stalled request's line waits
            # here, and a stop that joins its request threads cannot return before that line is in.
            with self.log._lock:
                stopping = self.stop_in_background()
                stopping.join(1.5)
                self.assertTrue(stopping.is_alive(), "the stop returned before the closed request logged its line")
            stopping.join(5)
            elapsed = time.monotonic() - began
        self.assertFalse(stopping.is_alive(), "a stalled client held the stop past its grace")
        return elapsed, err.getvalue()

    def test_a_stop_closes_a_request_stalled_in_its_body_once_the_grace_is_over(self):
        # A client frozen half way through its body (a killed app, a half-open connection) would hold
        # the join for ever, and the orchestrator's SIGTERM and wait with it.
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        self.addCleanup(connection.close)
        begin_put(connection, "stalled", b"0123456789", 5)
        elapsed, err = self.stop_past_the_grace([True])
        self.assertGreaterEqual(elapsed, 0.5, "the stop did not wait the grace for the request")
        self.assertEqual(err, "")
        lines = [json.loads(line) for line in self.log_path.read_text().splitlines()]
        self.assertEqual([(r["kind"], r["path"], r["status"], r["aborted"]) for r in lines],
                         [("unreadable", "/dav/stalled", 400, True)])
        self.assertFalse(Path(self.tmp.name, "state", "dav", "stalled").exists())
        with contextlib.suppress(ConnectionResetError):
            self.assertEqual(connection.sock.recv(1), b"")

    def test_a_stop_closes_a_connection_that_stopped_reading_its_answer_once_the_grace_is_over(self):
        # Far more than the loopback socket buffers hold, so the answer's write blocks on a client that
        # does not read it.
        self.assertEqual(self.dav("PUT", "big", b"z" * (32 << 20))[0], 201)
        # The PUT's connection stays busy until its line is logged, after its client has read the
        # answer; the wait below took it for the GET's, the stop began with the GET not yet read, and
        # the GET was never logged (3 failing runs under load, phase 4 unit GH). So it is gone first.
        self.wait_for_connections([])
        reader = socket.create_connection(("127.0.0.1", self.port))
        self.addCleanup(reader.close)
        reader.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        reader.sendall(f"GET {srv.DAV_PREFIX}big HTTP/1.1\r\nHost: fixture\r\n"
                       f"Authorization: {dav_authorization()['Authorization']}\r\n\r\n".encode())
        self.sent += 1
        _, err = self.stop_past_the_grace([True])
        self.assertEqual(err, "")
        got = [(r["kind"], r["status"], r["aborted"]) for r in self.records()]
        self.assertEqual(got, [("dav.put", 201, False), ("dav.get", 200, True)])


class MainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name, "state-root")
        self.log = Path(self.tmp.name, "log", "requests.jsonl")

    def run_main(self, *argv: str) -> tuple[int, str]:
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = srv.main(["--log", str(self.log), "--state-root", str(self.root), *argv])
        return code, err.getvalue()

    def start(self, *argv: str) -> tuple[subprocess.Popen, int]:
        process = subprocess.Popen([sys.executable, str(SERVER), "--log", str(self.log), "--state-root",
                                    str(self.root), "--port", "0", *argv],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(self.stop, process)
        line = process.stdout.readline()
        self.assertRegex(line, r"^ready \d+\n$")
        return process, int(line.split()[1])

    @staticmethod
    def stop(process: subprocess.Popen) -> None:
        if process.poll() is None:
            process.kill()
        process.communicate()

    def wait_for_lines(self, count: int) -> None:
        """Waits until the log holds [count] lines: the server logs a request after its client can read
        the answer, so two answered requests can be logged in either order unless each is waited for."""
        deadline = time.monotonic() + 5
        while len(self.log.read_text().splitlines()) < count:
            self.assertLess(time.monotonic(), deadline, f"the log never held {count} lines")
            time.sleep(0.01)

    def test_it_prints_a_ready_line_and_removes_its_state_when_stopped(self):
        process, port = self.start()
        start = json.loads(self.log.read_text().splitlines()[0])
        self.assertEqual((start["event"], start["port"]), ("start", port))
        state = Path(start["state_dir"])
        self.assertEqual(state.parent, self.root)
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        credentials = base64.b64encode(f"{srv.DEFAULT_DAV_USER}:{srv.DEFAULT_DAV_PASSWORD}".encode()).decode()
        connection.request("PUT", "/dav/kept", b"bytes", {"Authorization": f"Basic {credentials}"})
        self.assertEqual(connection.getresponse().status, 201)
        connection.close()
        self.assertEqual((state / "dav" / "kept").read_bytes(), b"bytes")

        process.send_signal(signal.SIGTERM)
        self.assertEqual(process.wait(10), 0)
        self.assertFalse(state.exists())
        self.assertEqual(list(self.root.iterdir()), [])
        lines = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual([line.get("event", line.get("kind")) for line in lines], ["start", "dav.put", "stop"])
        self.assertTrue(lines[-1]["state_removed"])
        self.assertNotIn(srv.DEFAULT_CLIENT_SECRET, self.log.read_text())

    def test_a_stop_waits_for_a_request_being_answered_and_closes_an_idle_connection(self):
        process, port = self.start()
        state = Path(json.loads(self.log.read_text().splitlines()[0])["state_dir"])
        idle = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        self.addCleanup(idle.close)
        idle.request("PUT", "/dav/idle", b"i", dav_authorization())
        response = idle.getresponse()
        response.read()
        self.assertEqual(response.status, 201)
        # Logged before the next request, or /dav/first's line could come first (10 failing runs under
        # load, phase 4 unit GH); the order below is the requests', not the scheduler's.
        self.wait_for_lines(2)
        slow = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        self.addCleanup(slow.close)
        # A first request starts this connection's handler, which then waits in its read, so the next
        # request line is read as it arrives; half a second is far more than that takes on loopback.
        slow.request("PUT", "/dav/first", b"f", dav_authorization())
        response = slow.getresponse()
        response.read()
        self.assertEqual(response.status, 201)
        self.wait_for_lines(3)
        body = b"0123456789"
        begin_put(slow, "slow", body, 5)
        time.sleep(0.5)

        process.send_signal(signal.SIGTERM)
        with self.assertRaises(subprocess.TimeoutExpired, msg="it stopped under a request being answered"):
            process.wait(0.5)
        self.assertTrue(state.exists())
        slow.send(body[5:])
        self.assertEqual(slow.getresponse().status, 201)
        self.assertEqual(process.wait(10), 0)
        _, err = process.communicate()
        self.assertEqual(err, "")
        self.assertFalse(state.exists())
        lines = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual([line.get("event", line.get("path")) for line in lines],
                         ["start", "/dav/idle", "/dav/first", "/dav/slow", "stop"])
        self.assertEqual(lines[3]["status"], 201)
        self.assertTrue(lines[-1]["state_removed"])

    def test_a_stop_ends_within_its_grace_under_a_client_stalled_in_its_body(self):
        # The gate's plain SIGTERM and wait, with a client that never sends the rest of its body.
        process, port = self.start()
        state = Path(json.loads(self.log.read_text().splitlines()[0])["state_dir"])
        stalled = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        self.addCleanup(stalled.close)
        begin_put(stalled, "stalled", b"0123456789", 5)
        time.sleep(1)  # accepted, its request line read

        began = time.monotonic()
        process.send_signal(signal.SIGTERM)
        with self.assertRaises(subprocess.TimeoutExpired, msg="it stopped without waiting for the request"):
            process.wait(0.5)
        self.assertEqual(process.wait(srv.STOP_GRACE_S + 5), 0)
        self.assertGreaterEqual(time.monotonic() - began, srv.STOP_GRACE_S)
        _, err = process.communicate()
        self.assertEqual(err, "")
        self.assertFalse(state.exists())
        lines = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual([line.get("event", line.get("path")) for line in lines], ["start", "/dav/stalled", "stop"])
        self.assertEqual((lines[1]["status"], lines[1]["aborted"]), (400, True))
        self.assertTrue(lines[-1]["state_removed"])

    def test_a_taken_port_is_exit_3_and_leaves_no_state(self):
        with socket.socket() as taken:
            taken.bind(("127.0.0.1", 0))
            taken.listen()
            code, err = self.run_main("--port", str(taken.getsockname()[1]))
        self.assertEqual(code, 3)
        self.assertIn("is another run holding it?", err)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_a_second_fixture_on_the_port_of_a_running_one_is_refused(self):
        process, port = self.start()
        # A subprocess with a limit, so a second server that did bind fails here instead of serving. A
        # bound one serves until stopped, so the limit only has to outlast an interpreter's start: 10 s
        # did not at load 68 (phase 4 unit GH).
        second = subprocess.run([sys.executable, str(SERVER), "--log", str(self.log), "--state-root", str(self.root),
                                 "--port", str(port)], capture_output=True, text=True, timeout=60)
        self.assertEqual(second.returncode, 3)
        self.assertIn(f"127.0.0.1:{port}", second.stderr)
        self.assertEqual(len(list(self.root.iterdir())), 1)
        process.send_signal(signal.SIGINT)
        self.assertEqual(process.wait(10), 0)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_the_latency_defaults_to_0_and_is_named_in_the_start_line(self):
        for argv, latency in (((), 0), (("--latency-ms", "60"), 60)):
            with self.subTest(argv=argv):
                self.log.unlink(missing_ok=True)
                process, _ = self.start(*argv)
                start = json.loads(self.log.read_text().splitlines()[0])
                self.assertEqual(start["latency_ms"], latency)
                process.send_signal(signal.SIGTERM)
                self.assertEqual(process.wait(10), 0)

    def test_a_blank_or_unusable_option_is_a_usage_error(self):
        for argv in (("--client-secret", ""), ("--dav-password", " "), ("--dav-user", "a:b"),
                     ("--access-token-lifetime", "0"), ("--latency-ms", "-1")):
            with self.subTest(argv=argv):
                code, err = self.run_main(*argv)
                self.assertEqual(code, 2)
                self.assertTrue(err)
        self.assertFalse(self.root.exists() and list(self.root.iterdir()))


if __name__ == "__main__":
    unittest.main()
