#!/usr/bin/env python3
"""Unit tests for tools/bench/mal_fixture_server.py, the MyAnimeList fake of phase 3 unit T2.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The server runs in process on a free port with a clock the tests move, as test_sync_fixture_server.py
runs its server, and is asked what the app asks: the authorization page with the plain PKCE pair and
the loopback, the code exchange, a refresh, the account, and the list calls the retry worker's push
makes. The command line runs as a subprocess, so its ready line and its refusals are the real ones.

The cases that matter, and why each one is here:
  - the authorization page answers 302 to the loopback with the code and the state only for the
    fixture's client, the plain method and a loopback redirect, since the device check's browser
    follows that redirect and a lax fake would pass a page the app built wrong;
  - the code is single use, expires, and needs the same redirect and a verifier equal to the
    challenge; a refresh replaces the refresh token and the old one is invalid_grant, which is the
    answer the app turns into "sign in again";
  - every /v2 call needs a live bearer token; the push's two calls (the entry with my_list_status,
    then the PUT) read and write one list, and DELETE removes it;
  - the log holds no token, code, verifier or state, and the PUT's fields are in it, since the
    device check reads the push from it;
  - the list read pages by limit, offset and the page size, with a paging.next naming the next
    entry's index while entries remain, answers list_status only when fields names it, and logs its
    four query values, since the comparison's device check (phase 4 unit TC) reads its paging from
    the log and a fake that answered one page would pass a loop that never pages;
  - the command line prints its ready line on a port the system picks, refuses 18796 (the tracker
    loopback port) and the other fixtures' ports, and a taken port is exit 3.
"""

from __future__ import annotations

import http.client
import importlib.util
import json
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlsplit

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS / "bench"
SERVER = BENCH / "mal_fixture_server.py"
sys.path.insert(0, str(BENCH))
_spec = importlib.util.spec_from_file_location("mal_fixture_server", SERVER)
srv = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = srv
_spec.loader.exec_module(srv)

CLIENT = srv.DEFAULT_CLIENT_ID
REDIRECT = "http://127.0.0.1:18796/callback"
FORM = {"Content-Type": "application/x-www-form-urlencoded"}


class Clock:
    def __init__(self, now: float = 1_790_000_000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now


class MalFixtureTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.log_path = Path(tmp.name, "mal.jsonl")
        self.log = srv.RequestLog(self.log_path)
        self.clock = Clock()
        self.fixture = srv.Fixture(clock=self.clock)
        self.server = srv.MalFixtureServer(("127.0.0.1", 0), self.log, self.fixture)
        self.port = self.server.server_address[1]
        self.requests = 0
        thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()

        def stop():
            self.server.shutdown()
            self.server.server_close()
            self.log.close()
            thread.join(5)

        self.addCleanup(stop)

    def call(self, method: str, path: str, body: dict | None = None, headers: dict | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        self.requests += 1
        try:
            payload = urlencode(body).encode() if body is not None else None
            connection.request(method, path, body=payload, headers={**(FORM if body is not None else {}), **(headers or {})})
            response = connection.getresponse()
            data = response.read()
            return response.status, dict(response.getheaders()), data
        finally:
            connection.close()

    def authorize(self, **overrides) -> tuple[int, dict]:
        query = {"client_id": CLIENT, "code_challenge": self.verifier, "code_challenge_method": "plain",
                 "response_type": "code", "redirect_uri": REDIRECT, "state": "the-state", **overrides}
        query = {key: value for key, value in query.items() if value is not None}
        status, headers, _ = self.call("GET", srv.AUTHORIZE_PATH + "?" + urlencode(query))
        return status, headers

    verifier = "v" * 43

    def code(self) -> str:
        status, headers = self.authorize()
        self.assertEqual(302, status)
        return parse_qs(urlsplit(headers["Location"]).query)["code"][0]

    def exchange(self, code: str, **overrides):
        form = {"client_id": CLIENT, "code": code, "code_verifier": self.verifier,
                "grant_type": "authorization_code", "redirect_uri": REDIRECT, **overrides}
        status, _, data = self.call("POST", srv.TOKEN_PATH, {k: v for k, v in form.items() if v is not None})
        return status, json.loads(data)

    def signed_in(self) -> dict:
        status, tokens = self.exchange(self.code())
        self.assertEqual(200, status)
        return tokens

    def bearer(self, tokens: dict) -> dict:
        return {"Authorization": f"Bearer {tokens['access_token']}"}

    def lines(self) -> list[dict]:
        # The server writes a request's line after it sends the response, from that request's own
        # thread, so the client can read the body before the line exists and two lines can land out
        # of order. Wait for one line per request the test made, then order them by arrival.
        deadline = time.monotonic() + 5
        while len(self.log_path.read_text().splitlines()) < self.requests and time.monotonic() < deadline:
            time.sleep(0.01)
        return sorted((json.loads(line) for line in self.log_path.read_text().splitlines()), key=lambda r: r["t0"])

    def test_the_authorization_page_redirects_to_the_loopback_with_a_code_and_the_state(self):
        status, headers = self.authorize()

        self.assertEqual(302, status)
        location = urlsplit(headers["Location"])
        self.assertEqual(("http", "127.0.0.1:18796", "/callback"), (location.scheme, location.netloc, location.path))
        query = parse_qs(location.query)
        self.assertEqual(["the-state"], query["state"])
        self.assertEqual(1, len(query["code"]))

    def test_the_authorization_page_refuses_what_the_app_must_not_send(self):
        cases = {
            "client_id": {"client_id": "another-client"},
            "response_type": {"response_type": "token"},
            "code_challenge_method": {"code_challenge_method": "S256"},
            "code_challenge": {"code_challenge": "short"},
            "redirect_uri": {"redirect_uri": "https://example.invalid/callback"},
            "state": {"state": None},
        }
        for wrong, overrides in cases.items():
            with self.subTest(wrong):
                status, headers = self.authorize(**overrides)
                self.assertEqual(400, status)
                self.assertNotIn("Location", headers)
        self.assertEqual(sorted(cases), sorted(line["refused"] for line in self.lines()))
        # A missing method is refused too: MyAnimeList's default is plain, but the app always names it.
        self.assertEqual(400, self.authorize(code_challenge_method=None)[0])

    def test_a_code_is_exchanged_once_with_the_same_redirect_and_a_verifier_equal_to_the_challenge(self):
        code = self.code()
        status, tokens = self.exchange(code)

        self.assertEqual(200, status)
        self.assertEqual({"token_type", "expires_in", "access_token", "refresh_token"}, set(tokens))
        self.assertEqual("Bearer", tokens["token_type"])
        self.assertEqual(srv.ACCESS_TOKEN_LIFETIME_S, tokens["expires_in"])
        self.assertEqual((400, "invalid_grant"), (lambda s, b: (s, b["error"]))(*self.exchange(code)))

        for overrides in ({"code_verifier": "w" * 43}, {"redirect_uri": "http://127.0.0.1:18796/other"}):
            with self.subTest(overrides):
                status, body = self.exchange(self.code(), **overrides)
                self.assertEqual((400, "invalid_grant"), (status, body["error"]))

    def test_a_code_expires_and_a_wrong_client_is_invalid_client(self):
        code = self.code()
        self.clock.now += srv.CODE_LIFETIME_S
        self.assertEqual(400, self.exchange(code)[0])

        status, body = self.exchange(self.code(), client_id="another-client")
        self.assertEqual((401, "invalid_client"), (status, body["error"]))
        status, body = self.exchange(self.code(), grant_type="password")
        self.assertEqual((400, "unsupported_grant_type"), (status, body["error"]))

    def test_a_refresh_replaces_the_refresh_token_and_the_old_one_is_invalid_grant(self):
        tokens = self.signed_in()
        form = {"client_id": CLIENT, "refresh_token": tokens["refresh_token"], "grant_type": "refresh_token"}

        status, _, data = self.call("POST", srv.TOKEN_PATH, form, self.bearer(tokens))
        renewed = json.loads(data)
        self.assertEqual(200, status)
        self.assertNotEqual(tokens["refresh_token"], renewed["refresh_token"])
        self.assertEqual(200, self.call("GET", "/v2/users/@me", headers=self.bearer(renewed))[0])

        status, _, data = self.call("POST", srv.TOKEN_PATH, form)
        self.assertEqual((400, "invalid_grant"), (status, json.loads(data)["error"]))

    def test_every_api_call_needs_a_live_bearer_token(self):
        tokens = self.signed_in()
        for headers in ({}, {"Authorization": "Bearer unknown"}, {"Authorization": tokens["access_token"]}):
            with self.subTest(headers):
                status, response_headers, _ = self.call("GET", "/v2/users/@me", headers=headers)
                self.assertEqual(401, status)
                self.assertIn("invalid_token", response_headers["WWW-Authenticate"])

        status, _, data = self.call("GET", "/v2/users/%40me", headers=self.bearer(tokens))
        self.assertEqual((200, srv.DEFAULT_USER_NAME), (status, json.loads(data)["name"]))

        self.clock.now += srv.ACCESS_TOKEN_LIFETIME_S
        self.assertEqual(401, self.call("GET", "/v2/users/@me", headers=self.bearer(tokens))[0])

    def test_the_push_reads_the_entry_then_writes_the_list_and_both_see_one_list(self):
        auth = self.bearer(self.signed_in())
        status, _, data = self.call("GET", "/v2/manga/1001?fields=num_chapters,my_list_status", headers=auth)
        entry = json.loads(data)
        self.assertEqual((200, 12), (status, entry["num_chapters"]))
        self.assertNotIn("my_list_status", entry)

        form = {"status": "reading", "is_rereading": "false", "score": "0.0", "num_chapters_read": "3",
                "start_date": "", "finish_date": ""}
        status, _, data = self.call("PUT", "/v2/manga/1001/my_list_status", form, auth)
        written = json.loads(data)
        self.assertEqual(200, status)
        self.assertEqual(("reading", False, 0, 3), tuple(written[k] for k in ("status", "is_rereading", "score",
                                                                           "num_chapters_read")))
        self.assertNotIn("start_date", written)

        entry = json.loads(self.call("GET", "/v2/manga/1001", headers=auth)[2])
        self.assertEqual(3, entry["my_list_status"]["num_chapters_read"])
        listed = json.loads(self.call("GET", "/v2/users/@me/mangalist?limit=250", headers=auth)[2])
        self.assertEqual([1001], [item["node"]["id"] for item in listed["data"]])

        put = [line for line in self.lines() if line.get("kind") == "list.put"]
        self.assertEqual(1, len(put))
        self.assertEqual((1001, "3", "reading", None), (put[0]["entry"], put[0]["form"]["num_chapters_read"],
                                                         put[0]["form"]["status"], put[0]["form"]["start_date"]))

        self.assertEqual(200, self.call("DELETE", "/v2/manga/1001/my_list_status", headers=auth)[0])
        self.assertEqual(404, self.call("DELETE", "/v2/manga/1001/my_list_status", headers=auth)[0])
        self.assertNotIn("my_list_status", json.loads(self.call("GET", "/v2/manga/1001", headers=auth)[2]))

    def test_bad_list_fields_an_unknown_entry_and_an_unknown_path_are_refused(self):
        auth = self.bearer(self.signed_in())
        self.assertEqual(400, self.call("PUT", "/v2/manga/1001/my_list_status", {"status": "watching"}, auth)[0])
        self.assertEqual(400, self.call("PUT", "/v2/manga/1001/my_list_status", {"num_chapters_read": "x"}, auth)[0])
        self.assertEqual(404, self.call("GET", "/v2/manga/9999", headers=auth)[0])
        self.assertEqual(404, self.call("PUT", "/v2/manga/9999/my_list_status", {"status": "reading"}, auth)[0])
        self.assertEqual(404, self.call("GET", "/v2/anime", headers=auth)[0])
        self.assertEqual(404, self.call("POST", "/nothing", {})[0])

    def test_the_list_pages_by_limit_offset_and_page_size_and_answers_list_status_only_when_asked(self):
        # Test 52 of the comparison's design (phase 4 unit TC).
        auth = self.bearer(self.signed_in())
        form = {"status": "reading", "num_chapters_read": "2"}
        for entry in (1004, 1001, 1003):
            self.assertEqual(200, self.call("PUT", f"/v2/manga/{entry}/my_list_status", form, auth)[0])

        def page(query: dict) -> dict:
            status, _, data = self.call("GET", "/v2/users/@me/mangalist?" + urlencode(query), headers=auth)
            self.assertEqual(200, status)
            return json.loads(data)

        def ids(body: dict) -> list[int]:
            return [item["node"]["id"] for item in body["data"]]

        fields = {"fields": "num_chapters,list_status", "limit": "2", "nsfw": "true"}
        first = page(fields)
        self.assertEqual([1001, 1003], ids(first))
        self.assertEqual([2, 2], [item["list_status"]["num_chapters_read"] for item in first["data"]])
        following = urlsplit(first["paging"]["next"])
        self.assertEqual(("http", f"127.0.0.1:{self.port}", "/v2/users/@me/mangalist"),
                         (following.scheme, following.netloc, following.path))
        self.assertEqual(["2"], parse_qs(following.query)["offset"])
        last = page({**fields, "offset": "2"})
        self.assertEqual([1004], ids(last))
        self.assertEqual({}, last["paging"])
        self.assertEqual([], ids(page({**fields, "offset": "3"})))

        bare = page({"limit": "250"})
        self.assertEqual([1001, 1003, 1004], ids(bare))
        self.assertNotIn("list_status", bare["data"][0])
        self.assertEqual({}, bare["paging"])

        self.fixture.list_page_size = 1
        capped = page({"limit": "250", "fields": "list_status"})
        self.assertEqual([1001], ids(capped))
        self.assertEqual(["1"], parse_qs(urlsplit(capped["paging"]["next"]).query)["offset"])
        self.assertEqual(["250"], parse_qs(urlsplit(capped["paging"]["next"]).query)["limit"])
        self.assertEqual(400, self.call("GET", "/v2/users/@me/mangalist?offset=-1", headers=auth)[0])

        logged = [{key: line[key] for key in ("offset", "limit", "fields", "nsfw")}
                  for line in self.lines() if line.get("kind") == "list.get"]
        self.assertEqual([
            {"offset": None, "limit": "2", "fields": "num_chapters,list_status", "nsfw": "true"},
            {"offset": "2", "limit": "2", "fields": "num_chapters,list_status", "nsfw": "true"},
            {"offset": "3", "limit": "2", "fields": "num_chapters,list_status", "nsfw": "true"},
            {"offset": None, "limit": "250", "fields": None, "nsfw": None},
            {"offset": None, "limit": "250", "fields": "list_status", "nsfw": None},
            {"offset": "-1", "limit": None, "fields": None, "nsfw": None},
        ], logged)

    def test_search_matches_titles(self):
        auth = self.bearer(self.signed_in())
        found = json.loads(self.call("GET", "/v2/manga?" + urlencode({"q": "fixture title", "limit": "10"}),
                                     headers=auth)[2])
        self.assertEqual([1001, 1002], [item["node"]["id"] for item in found["data"]])
        self.assertEqual("light_novel", found["data"][1]["node"]["media_type"])

    def test_the_log_holds_no_token_code_verifier_or_state(self):
        status, headers = self.authorize()
        code = parse_qs(urlsplit(headers["Location"]).query)["code"][0]
        tokens = self.exchange(code)[1]
        self.call("POST", srv.TOKEN_PATH, {"client_id": CLIENT, "refresh_token": tokens["refresh_token"],
                                           "grant_type": "refresh_token"})
        self.call("GET", "/v2/users/@me", headers=self.bearer(tokens))

        self.lines()  # waits for every request's line before the text is read
        text = self.log_path.read_text()
        for secret in (code, self.verifier, "the-state", tokens["access_token"], tokens["refresh_token"]):
            self.assertNotIn(secret, text)
        kinds = [line["kind"] for line in self.lines()]
        self.assertEqual(["oauth.authorize", "oauth.token", "oauth.token", "user.get"], kinds)
        self.assertEqual(["authorization_code", "refresh_token"], [line["grant"] for line in self.lines() if "grant" in line])


class CommandLineTest(unittest.TestCase):
    def run_server(self, *args: str):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        log = Path(tmp.name, "mal.jsonl")
        return subprocess.Popen([sys.executable, str(SERVER), "--log", str(log), *args], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True), log

    def test_it_prints_its_ready_line_on_a_port_the_system_picks_and_stops_on_sigterm(self):
        process, log = self.run_server()
        try:
            ready = process.stdout.readline().split()
            self.assertEqual("ready", ready[0])
            port = int(ready[1])
            self.assertNotIn(port, srv.RESERVED_PORTS)
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            connection.request("GET", "/v2/users/@me")
            self.assertEqual(401, connection.getresponse().status)
            connection.close()
        finally:
            process.terminate()
            self.assertEqual(0, process.wait(10))
            process.stdout.close()
            process.stderr.close()
        events = [json.loads(line).get("event") for line in log.read_text().splitlines()]
        self.assertEqual(("start", "stop"), (events[0], events[-1]))

    def test_it_refuses_the_tracker_loopback_port_and_the_other_fixtures(self):
        for port in (18796, 18795, 18794):
            with self.subTest(port):
                process, _ = self.run_server("--port", str(port))
                out, err = process.communicate(timeout=10)
                self.assertEqual(2, process.returncode)
                self.assertEqual("", out)
                self.assertIn("never binds", err)

    def test_a_taken_port_is_exit_3(self):
        with socket.socket() as taken:
            taken.bind(("127.0.0.1", 0))
            taken.listen()
            process, _ = self.run_server("--port", str(taken.getsockname()[1]))
            out, err = process.communicate(timeout=10)
        self.assertEqual(3, process.returncode)
        self.assertIn("cannot listen", err)

    def test_blank_options_are_usage_errors(self):
        for option in ("--client-id", "--user-name"):
            with self.subTest(option):
                process, _ = self.run_server(option, " ")
                process.communicate(timeout=10)
                self.assertEqual(2, process.returncode)
        process, _ = self.run_server("--list-page-size", "0")
        process.communicate(timeout=10)
        self.assertEqual(2, process.returncode)


if __name__ == "__main__":
    unittest.main()
