#!/usr/bin/env python3
"""Unit tests for the library update fixture of ruling R66: its parameters, its server, its backups.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The fixture has two halves that never meet in a build: the extension, compiled on its own under
tools/fixtures/update-extension, and the host side under tools/bench. They agree on the port, the
host names, the rate limit and the source names only because both were written with the same
numbers, so the first test reads the extension's Kotlin constants back out of its source and
compares them with update_fixture.py. A backup that named a source id the extension does not
produce would restore into a library no source can update, and the second group checks the ids the
backup writes against an independent reading of HttpSource.generateId.

The server is run in process on a free port with no latency, so its responses and its log are
tested as the device would see them. The backups are decoded field by field, with a decoder written
here rather than the encoder under test, and counted.
"""

from __future__ import annotations

import datetime
import gzip
import hashlib
import http.client
import importlib.util
import json
import re
import struct
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS / "bench"
FACTORY_KT = (TOOLS / "fixtures" / "update-extension" / "extension" / "src" / "main" / "kotlin" / "app" / "fixture"
              / "libraryupdate" / "UpdateFixtureFactory.kt")
MANIFEST = TOOLS / "fixtures" / "update-extension" / "extension" / "src" / "main" / "AndroidManifest.xml"
EXTENSION_BUILD = TOOLS / "fixtures" / "update-extension" / "extension" / "build.gradle.kts"
sys.path.insert(0, str(BENCH))


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, BENCH / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


fx = load("update_fixture")
server = load("update_fixture_server")
update_backup = load("update_backup")


def kotlin_constants(source: str) -> dict[str, str]:
    return {name: value.rstrip("L").strip('"') for name, value in re.findall(r"^const val (\w+) = (.+)$", source, re.M)}


class ParametersTest(unittest.TestCase):
    def test_the_extension_constants_are_the_host_constants(self):
        constants = kotlin_constants(FACTORY_KT.read_text())
        self.assertEqual(constants, {
            "SOURCE_COUNT": str(fx.SOURCE_COUNT),
            "HOST_SUFFIX": fx.HOST_SUFFIX,
            "SERVER_PORT": str(fx.SERVER_PORT),
            "RATE_LIMIT_PERMITS": str(fx.RATE_LIMIT_PERMITS),
            "RATE_LIMIT_PERIOD_SECONDS": str(fx.RATE_LIMIT_PERIOD_SECONDS),
        })

    def test_the_extension_names_its_sources_and_package_as_the_host_expects(self):
        source = FACTORY_KT.read_text()
        self.assertIn('override val name: String = "Update Fixture $label"', source)
        self.assertIn('override val lang: String = "en"', source)
        self.assertEqual(fx.source_name(7), "Update Fixture 07")
        self.assertEqual(fx.SOURCE_LANG, "en")
        self.assertIn(f'applicationId = "{fx.EXTENSION_PACKAGE}"', EXTENSION_BUILD.read_text())
        self.assertIn(f'android:label="{fx.EXTENSION_NAME}"', MANIFEST.read_text())

    def test_section_4_q1_numbers(self):
        self.assertEqual((fx.LATENCY_MIN_S, fx.LATENCY_MAX_S), (0.4, 0.8))
        # Mean latency times the limit is the 3 section 4 Q1 derives from.
        self.assertAlmostEqual((fx.LATENCY_MIN_S + fx.LATENCY_MAX_S) / 2 * fx.RATE_LIMIT_PERMITS, 3.0)
        self.assertEqual((fx.CHAPTERS_PER_LIST, fx.SEEDED_CHAPTERS), (50, 49))
        self.assertEqual({n: (s.sources, s.entries) for n, s in fx.SCENARIOS.items()},
                         {"2000x20": (20, 2000), "500x2": (2, 500)})
        for scenario in fx.SCENARIOS.values():
            self.assertLessEqual(scenario.per_source, fx.MAX_ENTRIES_PER_SOURCE)
            self.assertEqual(len(scenario.expected()), scenario.entries)

    def test_the_fixed_instants_are_the_dates_they_claim(self):
        utc = datetime.timezone.utc
        self.assertEqual(fx.UPLOAD_EPOCH_MS, int(datetime.datetime(2026, 1, 1, tzinfo=utc).timestamp() * 1000))
        self.assertEqual(fx.SEEDED_AT_MS, int(datetime.datetime(2026, 6, 1, tzinfo=utc).timestamp() * 1000))
        self.assertEqual(fx.chapter_uploaded_ms(50) - fx.chapter_uploaded_ms(1), 49 * fx.DAY_MS)

    def test_source_ids_follow_generate_id(self):
        # HttpSource.generateId: MD5 of "<name lowercased>/<lang>/<versionId>", the first eight bytes
        # big endian, and the sign bit cleared.
        digest = hashlib.md5(b"update fixture 01/en/1").digest()
        self.assertEqual(fx.source_id(1), struct.unpack(">Q", digest[:8])[0] & (2**63 - 1))
        ids = {fx.source_id(n) for n in range(1, fx.SOURCE_COUNT + 1)}
        self.assertEqual(len(ids), fx.SOURCE_COUNT)
        self.assertTrue(all(0 < i < 2**63 for i in ids))

    def test_host_names_map_back_to_their_source_and_nothing_else(self):
        self.assertEqual(fx.source_number_for_host(f"{fx.source_host(12)}:{fx.SERVER_PORT}"), 12)
        self.assertEqual(fx.source_number_for_host("S03.UPDATE-FIXTURE.TEST"), 3)
        for host in ("s21.update-fixture.test", "s00.update-fixture.test", "s1.update-fixture.test",
                     "x01.update-fixture.test", "s01.update-fixture.test.example", "10.0.2.2:18794", ""):
            self.assertIsNone(fx.source_number_for_host(host), host)


def fields(data: bytes) -> list[tuple[int, object]]:
    """(field number, value) of one protobuf message: an int for a varint, bytes for a length, a
    float for fixed32."""
    out, i = [], 0
    while i < len(data):
        key, i = varint(data, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = varint(data, i)
        elif wire == 2:
            length, i = varint(data, i)
            value, i = data[i:i + length], i + length
        elif wire == 5:
            value, i = struct.unpack("<f", data[i:i + 4])[0], i + 4
        else:
            raise AssertionError(f"wire type {wire} at byte {i}")
        out.append((number, value))
    return out


def varint(data: bytes, i: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[i]
        i += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return value, i


class BackupTest(unittest.TestCase):
    def test_each_scenario_seeds_its_entries_with_49_read_chapters(self):
        for name, scenario in fx.SCENARIOS.items():
            with self.subTest(name):
                top = fields(gzip.decompress(update_backup.backup(scenario)))
                mangas = [dict((n, v) for n, v in fields(m) if n != 16) | {16: [fields(c) for n, c in fields(m) if n == 16]}
                          for n, m in top if n == 1]
                sources = [dict(fields(s)) for n, s in top if n == 101]
                self.assertEqual(len(mangas), scenario.entries)
                self.assertEqual({(s[1].decode(), s[2]) for s in sources},
                                 {(fx.source_name(n), fx.source_id(n)) for n in range(1, scenario.sources + 1)})
                seeded = {(m[1], m[2].decode()) for m in mangas}
                self.assertEqual(seeded, {(fx.source_id(s), fx.entry_url(e)) for s, e in scenario.expected()})
                first = mangas[0]
                self.assertEqual((first[100], first[111], first[8], first[13]), (1, 1, 1, fx.SEEDED_AT_MS))
                self.assertEqual(len(first[16]), fx.SEEDED_CHAPTERS)
                chapter_49 = dict(first[16][-1])
                self.assertEqual(chapter_49[1].decode(), fx.chapter_url(1, 49))
                self.assertEqual((chapter_49[4], chapter_49[9], chapter_49[10]), (1, 49.0, 0))

    def test_the_same_scenario_writes_the_same_bytes(self):
        scenario = fx.SCENARIOS["500x2"]
        self.assertEqual(update_backup.backup(scenario), update_backup.backup(scenario))

    def test_a_negative_long_is_written_as_ten_bytes(self):
        self.assertEqual(update_backup.varint(-1), b"\xff" * 9 + b"\x01")


class LatencyTest(unittest.TestCase):
    def test_draws_are_seeded_per_request_and_occurrence(self):
        a, b = server.Latency(), server.Latency()
        first = [a.draw("s01.update-fixture.test", "/manga/1/chapters") for _ in range(2)]
        b.draw("s02.update-fixture.test", "/manga/9/chapters")  # another request first changes nothing
        self.assertEqual([b.draw("s01.update-fixture.test", "/manga/1/chapters") for _ in range(2)], first)
        self.assertNotEqual(first[0], first[1])
        draws = [a.draw("s03.update-fixture.test", f"/manga/{e}/chapters") for e in range(500)]
        self.assertTrue(all(fx.LATENCY_MIN_S <= d <= fx.LATENCY_MAX_S for d in draws))
        self.assertAlmostEqual(sum(draws) / len(draws), 0.6, delta=0.02)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        repo = Path(self.tmp.name, "repo")
        repo.mkdir()
        (repo / "update-fixture.apk").write_bytes(b"PK fixture")
        (repo / "signer.sha256").write_text("ab" * 32 + "\n")
        self.log_path = Path(self.tmp.name, "requests.jsonl")
        self.log = server.RequestLog(self.log_path)
        self.server = server.FixtureServer(("127.0.0.1", 0), self.log, server.Repository(repo), lambda host, path: 0.0,
                                           sleep=lambda _: None)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.log.close()

    def get(self, path: str, host: str) -> tuple[int, bytes]:
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=5)
        connection.request("GET", path, headers={"Host": host})
        response = connection.getresponse()
        body = response.read()
        connection.close()
        return response.status, body

    def records(self, count: int) -> list[dict]:
        # The server writes a request's line after it sends the response, from that request's own
        # thread, so the client can read the body before the line exists and two lines can land out
        # of order. Wait for the lines the test made, then order them by when each request arrived.
        deadline = time.monotonic() + 5
        while len(self.log_path.read_text().splitlines()) < count and time.monotonic() < deadline:
            time.sleep(0.01)
        return sorted((json.loads(line) for line in self.log_path.read_text().splitlines()), key=lambda r: r["t0"])

    def test_a_chapter_list_holds_50_chapters_newest_first_and_is_logged(self):
        status, body = self.get("/manga/7/chapters", f"{fx.source_host(4)}:{fx.SERVER_PORT}")
        self.assertEqual(status, 200)
        chapters = json.loads(body)["chapters"]
        self.assertEqual([c["number"] for c in chapters], list(range(50, 0, -1)))
        self.assertEqual(chapters[0]["url"], fx.chapter_url(7, 50))
        (logged,) = self.records(1)
        self.assertEqual({k: logged[k] for k in ("source", "source_id", "kind", "entry", "status", "aborted")},
                         {"source": 4, "source_id": str(fx.source_id(4)), "kind": "chapters", "entry": 7,
                          "status": 200, "aborted": False})
        self.assertLessEqual(logged["t0"], logged["t1"])

    def test_details_and_pages_are_served_and_logged_by_kind(self):
        host = fx.source_host(1)
        self.assertEqual(json.loads(self.get("/manga/3", host)[1])["title"], fx.entry_title(1, 3))
        pages = json.loads(self.get("/manga/3/chapter/2/pages", host)[1])["pages"]
        self.assertEqual(len(pages), fx.PAGES_PER_CHAPTER)
        status, png = self.get("/manga/3/chapter/2/page/0.png", host)
        self.assertEqual((status, png[:4]), (200, b"\x89PNG"))
        self.assertEqual([r["kind"] for r in self.records(3)], ["details", "pages", "image"])

    def test_popular_pages_through_every_entry_a_source_can_hold(self):
        body = json.loads(self.get("/popular?page=5", fx.source_host(2))[1])
        self.assertEqual(len(body["entries"]), fx.POPULAR_PAGE_SIZE)
        self.assertFalse(body["hasNextPage"])
        self.assertEqual(body["entries"][-1]["title"], fx.entry_title(2, fx.MAX_ENTRIES_PER_SOURCE))

    def test_hosts_and_entries_outside_the_fixture_are_refused(self):
        self.assertEqual(self.get("/manga/1/chapters", "s21.update-fixture.test")[0], 400)
        self.assertEqual(self.get("/manga/1/chapters", "10.0.2.2")[0], 404)
        self.assertEqual(self.get(f"/manga/{fx.MAX_ENTRIES_PER_SOURCE + 1}/chapters", fx.source_host(1))[0], 404)
        self.assertEqual([r["status"] for r in self.records(3)], [400, 404, 404])

    def test_the_repository_names_its_signer_and_serves_the_apk(self):
        repo = json.loads(self.get("/repo/repo.json", "10.0.2.2")[1])
        self.assertEqual(repo["meta"]["signingKeyFingerprint"], "ab" * 32)
        (entry,) = json.loads(self.get("/repo/index.min.json", "10.0.2.2")[1])
        self.assertEqual(entry["pkg"], fx.EXTENSION_PACKAGE)
        self.assertEqual(self.get(f"/repo/apk/{entry['apk']}", "10.0.2.2"), (200, b"PK fixture"))
        logged = self.records(3)
        self.assertEqual(len(logged), 3)
        self.assertTrue(all(r["kind"] == "repo" and r["source"] is None for r in logged))

    def test_without_a_repository_directory_nothing_is_installable(self):
        self.assertEqual(server.Repository(None).respond("/repo/index.min.json")[0], 404)


if __name__ == "__main__":
    unittest.main()
