#!/usr/bin/env python3
"""Unit tests for tools/bench/migrate_fixture_server.py, the batch migration's fixture server.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The server is the frozen update_fixture_server.py (ruling R66) with its catalogue replaced and a
control path added, so these run it in process on a free port (port 0) with no latency, as
test_download_hold_server.py runs its wrapper, and ask what the fixture extension asks: a search,
a listing, an entry's details, its chapters, a page list and a page.

The cases that matter, and why each one is here:
  - a title is found on the targets its role names and nowhere else, word by word too, since the
    advanced search queries single words and a shared word would match the wrong entry;
  - a bracketed title is not found by itself, and is found once the brackets are stripped, which is
    what separates the two search modes on the device;
  - the title on both targets has more chapters on C, which is what chapter-number matching picks;
  - the duplicate pair is one title on two old sources, and the 300-entry run has 300 old entries;
  - a window of searches answers 503 and the next is served; chosen chapter lists answer 503 until
    cleared; both are set through the control path and counted in its state, beside the searches,
    details and chapter lists each source answered, which the device reads to tell a repeat;
  - pages and images are the frozen server's, and the frozen function is back after the server
    stops, so the frozen server's own tests in the same process see the frozen catalogue;
  - a taken port is exit 3 and a bad failure spec a usage error, before anything is served.
"""

from __future__ import annotations

import contextlib
import http.client
import importlib.util
import io
import json
import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.parse import quote

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS / "bench"
sys.path.insert(0, str(BENCH))
_spec = importlib.util.spec_from_file_location("migrate_fixture_server", BENCH / "migrate_fixture_server.py")
server_module = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = server_module
_spec.loader.exec_module(server_module)
fx = server_module.fx
frozen = server_module.frozen
A, OVERFLOW, B, C = (server_module.OLD_A, server_module.OLD_OVERFLOW, server_module.TARGET_B,
                     server_module.TARGET_C)


def host(source: int) -> str:
    return f"{fx.source_host(source)}:{fx.SERVER_PORT}"


class CatalogueTest(unittest.TestCase):
    """The catalogue alone, with no server."""

    def setUp(self):
        self.entries = server_module.build_catalogue()

    def test_the_old_sources_hold_three_hundred_entries_and_the_duplicate_partner(self):
        self.assertEqual(len(self.entries[A]), 250)
        self.assertEqual(sorted(self.entries[OVERFLOW]), list(range(1, 52)))
        runnable = [e for e in self.entries[OVERFLOW].values() if e.role != "duplicate"]
        self.assertEqual(len(self.entries[A]) + len(runnable), 300)
        partner = self.entries[OVERFLOW][server_module.DUPLICATE_ENTRY]
        self.assertEqual(partner.title, self.entries[A][server_module.DUPLICATE_OF].title)

    def test_every_word_belongs_to_one_title_except_where_a_title_is_meant_to_repeat(self):
        owners: dict[str, set[str]] = {}
        for entries in self.entries.values():
            for entry in entries.values():
                base = entry.title.split(" (")[0]
                for w in entry.title.replace("(", " ").replace(")", " ").lower().split():
                    owners.setdefault(w, set()).add(base)
        shared = {w: titles for w, titles in owners.items() if len(titles) > 1}
        self.assertEqual(shared, {})
        self.assertTrue(all(len(w) == 6 for w in owners), "a word of another length could be a substring of one")

    def test_each_old_entry_names_the_target_entries_holding_its_title(self):
        roles = {}
        for entry in list(self.entries[A].values()) + list(self.entries[OVERFLOW].values()):
            roles[entry.role] = roles.get(entry.role, 0) + 1
            base = entry.title.split(" (")[0]
            for source, number in ((B, entry.on_b), (C, entry.on_c)):
                if number is not None:
                    self.assertEqual(self.entries[source][number].title, base)
            expected = {"b": (True, False), "c": (False, True), "both": (True, True), "none": (False, False),
                        "bracketed": (True, False), "duplicate": (True, False)}[entry.role]
            self.assertEqual((entry.on_b is not None, entry.on_c is not None), expected, entry)
        self.assertEqual(roles, {"b": 150, "c": 50, "both": 50, "none": 30, "bracketed": 20, "duplicate": 1})
        self.assertLessEqual(len(self.entries[B]), fx.MAX_ENTRIES_PER_SOURCE)
        self.assertLessEqual(len(self.entries[C]), fx.MAX_ENTRIES_PER_SOURCE)

    def test_the_failure_specs_parse_and_refuse_what_is_not_a_fixture_source(self):
        self.assertEqual(server_module.parse_fail_chapters("3:4-6, 4:9"), {(3, 4), (3, 5), (3, 6), (4, 9)})
        self.assertEqual(server_module.parse_fail_chapters(""), set())
        self.assertEqual(server_module.parse_unavailable("3:12"), (3, 12))
        for bad in ("3", "x:1", "21:1", "3:6-4"):
            with self.assertRaises(ValueError):
                server_module.parse_fail_chapters(bad)
        for bad in ("3", "0:2", "3:-1"):
            with self.assertRaises(ValueError):
                server_module.parse_unavailable(bad)


class MigrateFixtureServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.log_path = Path(self.tmp.name, "requests.jsonl")
        self.log = frozen.RequestLog(self.log_path)
        self.catalogue = server_module.Catalogue()
        self.latencies: list[tuple[str, str]] = []
        self.server = server_module.MigrateFixtureServer(
            ("127.0.0.1", 0), self.log, self.catalogue,
            lambda h, p: self.latencies.append((h, p)) or 0.0, sleep=lambda _: None,
        )
        self.addCleanup(server_module.install(self.catalogue))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.close)
        self.entries = self.catalogue.entries

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.log.close()

    def get(self, source: int | None, path: str) -> tuple[int, bytes]:
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=5)
        connection.request("GET", path, headers={"Host": host(source) if source else "10.0.2.2"})
        response = connection.getresponse()
        body = response.read()
        connection.close()
        return response.status, body

    def search(self, source: int, query: str) -> list[str]:
        status, body = self.get(source, f"/popular?page=1&q={quote(query)}")
        self.assertEqual(status, 200)
        return [e["title"] for e in json.loads(body)["entries"]]

    def control(self, query: str = "") -> dict:
        status, body = self.get(None, f"/control{query}")
        self.assertEqual(status, 200, body)
        return json.loads(body)

    def records(self, count: int) -> list[dict]:
        """The log's records once it holds [count] of them, or what it holds after 5 s.

        A handler writes its record after the response is flushed (the record carries whether the
        write was aborted), so the client can read the response before its line is in the log.
        Seen in the staging gate on 2026-10-11 under a load average of 60: the control line was
        missing in 3 of 5 runs.
        """
        deadline = time.monotonic() + 5
        while True:
            lines = self.log_path.read_text().splitlines()
            if len(lines) >= count or time.monotonic() > deadline:
                return [json.loads(line) for line in lines]
            time.sleep(0.02)

    def test_a_title_is_found_on_the_targets_its_role_names_and_nowhere_else(self):
        for number, found in ((1, {B}), (101, {C}), (151, {B, C}), (201, set())):
            title = self.entries[A][number].title
            for source in (B, C, 5):
                expected = [title] if source in found else []
                self.assertEqual(self.search(source, title), expected, (number, source))
                for w in title.split():
                    self.assertEqual(self.search(source, w), expected, (number, source, w))

    def test_a_bracketed_title_is_found_only_once_its_brackets_are_stripped(self):
        entry = self.entries[A][231]
        base = entry.title.split(" (")[0]
        self.assertNotEqual(entry.title, base)
        self.assertEqual(self.search(B, entry.title), [])
        self.assertEqual(self.search(B, base), [base])
        self.assertEqual(self.search(C, base), [])

    def test_the_shared_title_has_more_chapters_on_c_and_every_list_is_numbered_from_one(self):
        entry = self.entries[A][151]

        def numbers(source: int, number: int) -> list[int]:
            status, body = self.get(source, f"/manga/{number}/chapters")
            self.assertEqual(status, 200)
            return [c["number"] for c in json.loads(body)["chapters"]]

        self.assertEqual(numbers(B, entry.on_b), list(range(10, 0, -1)))
        self.assertEqual(numbers(C, entry.on_c), list(range(20, 0, -1)))
        self.assertEqual(numbers(A, 151), list(range(12, 0, -1)))
        status, body = self.get(B, f"/manga/{entry.on_b}")
        self.assertEqual((status, json.loads(body)["title"]), (200, entry.title))
        self.assertEqual(self.get(C, "/manga/249")[0], 404)

    def test_the_listing_pages_through_a_source_and_other_sources_list_nothing(self):
        status, body = self.get(A, "/popular?page=5")
        page = json.loads(body)
        self.assertEqual((status, len(page["entries"]), page["hasNextPage"]), (200, 50, False))
        self.assertEqual(page["entries"][-1], {"url": "/manga/250", "title": self.entries[A][250].title})
        status, body = self.get(9, "/popular?page=1")
        self.assertEqual(json.loads(body), {"entries": [], "hasNextPage": False})

    def test_a_window_of_searches_answers_503_and_the_next_one_is_served(self):
        title = self.entries[A][151].title
        state = self.control("?unavailable=3:2")
        self.assertEqual(state["unavailable"], {"3": 2})
        self.assertEqual(self.get(B, "/popular?page=1")[0], 200, "a listing is not a search")
        self.assertEqual(self.get(B, f"/popular?page=1&q={quote(title)}")[0], 503)
        self.assertEqual(self.search(C, title), [title], "another source stays up")
        self.assertEqual(self.get(B, f"/popular?page=1&q={quote(title)}")[0], 503)
        self.assertEqual(self.search(B, title), [title])
        state = self.control()
        self.assertEqual({k: state[k] for k in ("unavailable", "unavailableAnswered", "failChapters", "failedChapters")},
                         {"unavailable": {}, "unavailableAnswered": 2, "failChapters": [], "failedChapters": 0})
        self.assertEqual(state["answered"]["search"], {"3": 3, "4": 1}, "a 503 is counted, a listing is not")
        self.assertEqual(state["searches"], [{"source": 3, "q": title}, {"source": 4, "q": title},
                                             {"source": 3, "q": title}, {"source": 3, "q": title}])

    def test_chosen_chapter_lists_fail_until_cleared(self):
        self.assertEqual(self.control("?fail_chapters=3:4-5")["failChapters"], ["3:4", "3:5"])
        self.assertEqual(self.get(B, "/manga/4/chapters")[0], 503)
        self.assertEqual(self.get(B, "/manga/5/chapters")[0], 503)
        self.assertEqual(self.get(B, "/manga/6/chapters")[0], 200)
        self.assertEqual(self.get(C, "/manga/4/chapters")[0], 200)
        self.assertEqual(self.get(B, "/manga/4")[0], 200, "details stay up")
        self.assertEqual(self.control("?fail_chapters=")["failChapters"], [])
        self.assertEqual(self.get(B, "/manga/4/chapters")[0], 200)
        state = self.control()
        self.assertEqual(state["failedChapters"], 2)
        self.assertEqual(state["answered"], {"search": {}, "details": {"3": 1}, "chapters": {"3": 4, "4": 1}})

    def test_reset_clears_both_failures_and_a_bad_spec_is_refused(self):
        self.control("?unavailable=4:9&fail_chapters=4:1")
        self.assertEqual(self.get(None, "/control?unavailable=4")[0], 400)
        state = self.control("?reset=1")
        self.assertEqual((state["unavailable"], state["failChapters"]), ({}, []))

    def test_the_catalogue_path_lists_every_entry_with_its_targets(self):
        status, body = self.get(None, "/control/catalogue")
        sources = json.loads(body)["sources"]
        self.assertEqual(status, 200)
        self.assertEqual(sorted(sources), ["1", "2", "3", "4"])
        first = sources["1"][0]
        self.assertEqual(first, {"source": 1, "entry": 1, "title": self.entries[A][1].title, "role": "b",
                                 "chapters": 12, "targets": {"3": 1, "4": None}})
        self.assertNotIn("targets", sources["3"][0])

    def test_pages_and_images_are_the_frozen_servers_and_every_request_is_logged(self):
        status, body = self.get(B, "/manga/3/chapter/2/pages")
        pages = json.loads(body)["pages"]
        self.assertEqual((status, len(pages)), (200, fx.PAGES_PER_CHAPTER))
        self.assertTrue(pages[0].endswith("/manga/3/chapter/2/page/0.png"))
        status, body = self.get(B, "/manga/3/chapter/2/page/0.png")
        self.assertEqual((status, body[:4]), (200, b"\x89PNG"))
        self.control()
        kinds = [(r["kind"], r["source"], r["status"]) for r in self.records(3)]
        self.assertEqual(kinds, [("pages", B, 200), ("image", B, 200), ("control", None, 200)])
        self.assertEqual(len(self.latencies), 2, "only source requests draw a latency")

    def test_the_frozen_function_is_back_once_the_install_is_undone(self):
        undo = server_module.install(server_module.Catalogue())
        self.assertIsNot(frozen.source_response, server_module.FROZEN_SOURCE_RESPONSE)
        undo()
        self.assertEqual(frozen.source_response, self.catalogue.respond)


class FrozenAfterwardsTest(unittest.TestCase):
    def test_nothing_leaves_the_frozen_catalogue_replaced(self):
        self.assertIs(frozen.source_response, server_module.FROZEN_SOURCE_RESPONSE)


class MainTest(unittest.TestCase):
    def run_main(self, *argv: str) -> tuple[int, str]:
        err = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(err):
            code = server_module.main(["--log", str(Path(tmp, "log.jsonl")), *argv])
        return code, err.getvalue()

    def test_a_bad_failure_spec_is_a_usage_error(self):
        code, err = self.run_main("--port", "0", "--fail-chapters", "3:9-2")
        self.assertEqual(code, 2)
        self.assertIn("not S:E or S:E-F", err)

    def test_a_taken_port_is_exit_3_and_leaves_the_frozen_function_in_place(self):
        with socket.socket() as taken:
            taken.bind(("127.0.0.1", 0))
            taken.listen()
            code, err = self.run_main("--port", str(taken.getsockname()[1]))
        self.assertEqual(code, 3)
        self.assertIn("is another run holding it?", err)
        self.assertIs(frozen.source_response, server_module.FROZEN_SOURCE_RESPONSE)


if __name__ == "__main__":
    unittest.main()
