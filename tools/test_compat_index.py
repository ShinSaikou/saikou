#!/usr/bin/env python3
"""Unit tests for tools/compat/compat_job.py: the catalog index reader (decode_index,
resolve_index) and the reader that answers what the host provides (dex_class_names,
apk_class_index, jar_class_index).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Builds protobuf bytes with a tiny local encoder (length-delimited and varint fields
only, matching what index_fields.json describes) instead of adding a protobuf
dependency, and assembles dex files and APKs byte by byte with struct and zipfile.
All URLs and package names are synthetic; tools/compat.fetch is patched with a dict
of url to bytes, so nothing here touches the network, a device or Gradle.
"""

from __future__ import annotations

import contextlib
import gzip
import importlib.util
import io
import json
import struct
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest.mock import MagicMock, patch

TOOLS = Path(__file__).resolve().parent
COMPAT_JOB_PATH = TOOLS / "compat" / "compat_job.py"

_spec = importlib.util.spec_from_file_location("compat_job", COMPAT_JOB_PATH)
compat_job = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(compat_job)

FIELDS = compat_job.FIELDS


# --- tiny protobuf encoder: length-delimited fields and varints only -----------------------------

def encode_varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def encode_tag(field_number: int, wire_type: int) -> bytes:
    return encode_varint((field_number << 3) | wire_type)


def ld_field(field_number: int, payload: bytes) -> bytes:
    return encode_tag(field_number, 2) + encode_varint(len(payload)) + payload


def string_field(field_number: int, value: str) -> bytes:
    return ld_field(field_number, value.encode("utf-8"))


def varint_field(field_number: int, value: int) -> bytes:
    return encode_tag(field_number, 0) + encode_varint(value)


def build_resources(jar_url: str, apk_url: str = "") -> bytes:
    payload = b""
    if apk_url:
        payload += string_field(FIELDS["Resources"]["apkUrl"], apk_url)
    payload += string_field(FIELDS["Resources"]["jarUrl"], jar_url)
    return payload


def build_source(source_id: int) -> bytes:
    return varint_field(FIELDS["Source"]["id"], source_id)


def build_extension(
    package: str,
    version_code: int,
    lib_version: str,
    jar_url: str,
    apk_url: str = "",
    sources: int = 0,
) -> bytes:
    payload = b""
    payload += string_field(FIELDS["Extension"]["packageName"], package)
    payload += ld_field(FIELDS["Extension"]["resources"], build_resources(jar_url, apk_url))
    payload += string_field(FIELDS["Extension"]["extensionLib"], lib_version)
    payload += varint_field(FIELDS["Extension"]["versionCode"], version_code)
    for source_id in range(sources):
        payload += ld_field(FIELDS["Extension"]["sources"], build_source(source_id))
    return payload


def build_index(entries: list[dict], signing_key: str | None = "ab" * 32) -> bytes:
    """Builds the bytes of an Index message. `signing_key=None` omits field 3 entirely,
    which is the shape decode_index must survive without crashing."""
    extension_list_payload = b""
    for entry in entries:
        extension_list_payload += ld_field(
            FIELDS["ExtensionList"]["extensions"], build_extension(**entry)
        )
    index = b""
    if signing_key is not None:
        index += string_field(FIELDS["Index"]["signingKey"], signing_key)
    index += ld_field(FIELDS["Index"]["extensionList"], extension_list_payload)
    return index


SAMPLE_ENTRY = {
    "package": "com.example.sample.foo",
    "version_code": 7,
    "lib_version": "1.2",
    "jar_url": "https://example.invalid/jars/foo.jar",
    "apk_url": "https://example.invalid/apk/foo.apk",
    "sources": 2,
}
SAMPLE_ENTRY_DECODED = {
    "package": "com.example.sample.foo",
    "versionCode": 7,
    "libVersion": "1.2",
    "jarUrl": "https://example.invalid/jars/foo.jar",
    "apkUrl": "https://example.invalid/apk/foo.apk",
    "sources": 2,
}
SIGNING_KEY = "ab" * 32


def patched_fetch(urls: dict[str, bytes]):
    """Returns (patcher_target, calls) where calls records every url fetched, in order."""
    calls: list[str] = []

    def fake(url: str, retries: int = 3) -> bytes:
        calls.append(url)
        if url not in urls:
            raise AssertionError(f"unexpected fetch of {url!r}, known urls: {sorted(urls)}")
        return urls[url]

    return fake, calls


class DecodeIndexTest(unittest.TestCase):

    def test_returns_signing_key_from_field_3_and_entries_from_field_101(self):
        raw = build_index([SAMPLE_ENTRY], signing_key=SIGNING_KEY)
        signing_key, entries = compat_job.decode_index(raw)
        self.assertEqual(signing_key, SIGNING_KEY)
        self.assertEqual(entries, [SAMPLE_ENTRY_DECODED])

    def test_reads_package_versioncode_libversion_and_jarurl_from_the_right_fields(self):
        other_entry = {
            "package": "com.example.sample.bar",
            "version_code": 42,
            "lib_version": "2.0",
            "jar_url": "https://example.invalid/jars/bar.jar",
        }
        raw = build_index([SAMPLE_ENTRY, other_entry], signing_key=SIGNING_KEY)
        _, entries = compat_job.decode_index(raw)
        self.assertEqual(entries[0]["package"], "com.example.sample.foo")
        self.assertEqual(entries[0]["versionCode"], 7)
        self.assertEqual(entries[0]["libVersion"], "1.2")
        self.assertEqual(entries[0]["jarUrl"], "https://example.invalid/jars/foo.jar")
        self.assertEqual(entries[1]["package"], "com.example.sample.bar")
        self.assertEqual(entries[1]["versionCode"], 42)
        self.assertEqual(entries[1]["libVersion"], "2.0")
        self.assertEqual(entries[1]["jarUrl"], "https://example.invalid/jars/bar.jar")

    def test_returns_an_empty_signing_key_when_field_3_is_absent(self):
        raw = build_index([SAMPLE_ENTRY], signing_key=None)
        signing_key, entries = compat_job.decode_index(raw)
        self.assertEqual(signing_key, "")
        self.assertEqual(entries, [SAMPLE_ENTRY_DECODED])


def fake_response(body: bytes) -> MagicMock:
    """A stand-in for the object urlopen hands back: usable as a context manager, and
    .read() returns the given body."""
    response = MagicMock()
    response.read.return_value = body
    response.__enter__.return_value = response
    response.__exit__.return_value = False
    return response


def fake_http_error(url: str, code: int, msg: str) -> urllib.error.HTTPError:
    """A stand-in for the error urlopen raises on a non-2xx status.

    HTTPError is also a file-like addinfourl, so its __init__ wraps `fp` (or, if `fp` is
    None, an empty BytesIO it makes itself) in a tempfile._TemporaryFileWrapper either
    way; if that wrapper is never closed, the garbage collector prints a ResourceWarning
    when the error is dropped. compat_job.fetch only ever reads exc.code and exc.reason
    off the errors built here, never the body, so it is safe to pass a real empty file
    object as `fp` and close the error immediately: `exc.reason` still returns `msg`
    unchanged either way, which is what tools/compat/compat_job.py interpolates into the
    message it dies with."""
    error = urllib.error.HTTPError(url, code, msg, None, io.BytesIO(b""))
    error.close()
    return error


class FetchTest(unittest.TestCase):
    """compat_job.fetch is the one place in the job that touches the network, and every
    other test in this file patches it away, so it gets no coverage anywhere else.
    urllib.request.urlopen and time.sleep are patched here; no test in this class makes
    a real request or actually sleeps."""

    def test_a_200_on_the_first_attempt_returns_the_body_and_makes_one_request(self):
        url = "https://example.invalid/index.pb"
        mock_urlopen = MagicMock(side_effect=[fake_response(b"the body")])
        with patch("urllib.request.urlopen", mock_urlopen), patch("time.sleep") as mock_sleep:
            result = compat_job.fetch(url)
        self.assertEqual(result, b"the body")
        self.assertEqual(mock_urlopen.call_count, 1)
        mock_sleep.assert_not_called()

    def test_a_retryable_status_that_then_succeeds_returns_the_body(self):
        url = "https://example.invalid/index.pb"
        for code in (500, 503):
            with self.subTest(code=code):
                error = fake_http_error(url, code, "server hiccup")
                mock_urlopen = MagicMock(side_effect=[error, fake_response(b"eventual body")])
                with patch("urllib.request.urlopen", mock_urlopen), patch("time.sleep") as mock_sleep:
                    result = compat_job.fetch(url)
                self.assertEqual(result, b"eventual body")
                self.assertEqual(mock_urlopen.call_count, 2)
                mock_sleep.assert_called_once_with(1)

    def test_a_retryable_status_that_never_clears_exhausts_retries_and_raises_systemexit_3(self):
        url = "https://example.invalid/index.pb"
        retries = 6
        errors = [fake_http_error(url, 503, "still down") for _ in range(retries)]
        mock_urlopen = MagicMock(side_effect=errors)
        stderr = io.StringIO()
        with patch("urllib.request.urlopen", mock_urlopen), patch("time.sleep"):
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.fetch(url, retries=retries)
        self.assertEqual(cm.exception.code, 3)
        self.assertIn(url, stderr.getvalue())
        self.assertEqual(mock_urlopen.call_count, retries)

    def test_a_non_retryable_404_raises_after_exactly_one_request(self):
        url = "https://example.invalid/missing.pb"
        error = fake_http_error(url, 404, "Not Found")
        mock_urlopen = MagicMock(side_effect=[error])
        stderr = io.StringIO()
        with patch("urllib.request.urlopen", mock_urlopen) as patched_urlopen, \
                patch("time.sleep") as mock_sleep:
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.fetch(url)
        self.assertEqual(cm.exception.code, 3)
        self.assertIn(url, stderr.getvalue())
        # The regression that matters: HTTPError is a URLError, so if the except clauses
        # are ever reordered, a 404 gets treated as retryable instead of failing fast.
        self.assertEqual(patched_urlopen.call_count, 1)
        mock_sleep.assert_not_called()

    def test_a_non_retryable_403_raises_after_exactly_one_request(self):
        url = "https://example.invalid/forbidden.pb"
        error = fake_http_error(url, 403, "Forbidden")
        mock_urlopen = MagicMock(side_effect=[error])
        stderr = io.StringIO()
        with patch("urllib.request.urlopen", mock_urlopen) as patched_urlopen, \
                patch("time.sleep") as mock_sleep:
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.fetch(url)
        self.assertEqual(cm.exception.code, 3)
        self.assertEqual(patched_urlopen.call_count, 1)
        mock_sleep.assert_not_called()

    def test_a_connection_error_is_retried_then_fails_naming_the_url(self):
        url = "https://example.test/index.pb"
        retries = 3
        errors = [urllib.error.URLError("connection refused") for _ in range(retries)]
        mock_urlopen = MagicMock(side_effect=errors)
        stderr = io.StringIO()
        with patch("urllib.request.urlopen", mock_urlopen), patch("time.sleep"):
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.fetch(url, retries=retries)
        self.assertEqual(cm.exception.code, 3)
        self.assertIn(url, stderr.getvalue())
        self.assertEqual(mock_urlopen.call_count, retries)

    def test_a_timeout_is_retried_then_fails_naming_the_url(self):
        url = "https://example.test/index.pb"
        retries = 3
        errors = [TimeoutError("timed out") for _ in range(retries)]
        mock_urlopen = MagicMock(side_effect=errors)
        stderr = io.StringIO()
        with patch("urllib.request.urlopen", mock_urlopen), patch("time.sleep"):
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.fetch(url, retries=retries)
        self.assertEqual(cm.exception.code, 3)
        self.assertIn(url, stderr.getvalue())
        self.assertEqual(mock_urlopen.call_count, retries)

    def test_backoff_sleeps_are_bounded_and_none_follows_the_final_attempt(self):
        url = "https://example.invalid/index.pb"
        DEFAULT_RETRIES = 6  # the shipped default; the gate calls fetch(url) with no retries
        # argument at all, so that default is the value under test here, not a stand-in for it.
        errors = [fake_http_error(url, 503, "still down") for _ in range(DEFAULT_RETRIES)]
        mock_urlopen = MagicMock(side_effect=errors)
        stderr = io.StringIO()
        with patch("urllib.request.urlopen", mock_urlopen), patch("time.sleep") as mock_sleep:
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit):
                    compat_job.fetch(url)
        self.assertEqual(mock_urlopen.call_count, DEFAULT_RETRIES)
        sleep_calls = [call.args[0] for call in mock_sleep.call_args_list]
        self.assertEqual(sleep_calls, [1, 2, 4, 8, 16])


class ResolveIndexTest(unittest.TestCase):

    def test_decodes_a_plain_protobuf_body(self):
        url = "https://example.invalid/index.pb"
        raw = build_index([SAMPLE_ENTRY], signing_key=SIGNING_KEY)
        fake, calls = patched_fetch({url: raw})
        with patch.object(compat_job, "fetch", side_effect=fake):
            signing_key, entries = compat_job.resolve_index(url)
        self.assertEqual(signing_key, SIGNING_KEY)
        self.assertEqual(entries, [SAMPLE_ENTRY_DECODED])
        self.assertEqual(calls, [url])

    def test_decodes_a_gzipped_protobuf_body(self):
        url = "https://example.invalid/index.pb.gz"
        raw = build_index([SAMPLE_ENTRY], signing_key=SIGNING_KEY)
        gzipped = gzip.compress(raw)
        fake, calls = patched_fetch({url: gzipped})
        with patch.object(compat_job, "fetch", side_effect=fake):
            signing_key, entries = compat_job.resolve_index(url)
        self.assertEqual(signing_key, SIGNING_KEY)
        self.assertEqual(entries, [SAMPLE_ENTRY_DECODED])
        self.assertEqual(calls, [url])

    def test_follows_an_index_v2_descriptor_exactly_one_hop(self):
        descriptor_url = "https://example.invalid/repo.json"
        v2_url = "https://example.invalid/v2/index.pb"
        descriptor = json.dumps({"index_v2": v2_url}).encode("utf-8")
        raw_index = build_index([SAMPLE_ENTRY], signing_key=SIGNING_KEY)
        fake, calls = patched_fetch({descriptor_url: descriptor, v2_url: raw_index})
        with patch.object(compat_job, "fetch", side_effect=fake):
            signing_key, entries = compat_job.resolve_index(descriptor_url)
        self.assertEqual(signing_key, SIGNING_KEY)
        self.assertEqual(entries, [SAMPLE_ENTRY_DECODED])
        # Exactly these two urls, in this order: proof of one hop rather than "eventually got there".
        self.assertEqual(calls, [descriptor_url, v2_url])

    def test_refuses_a_descriptor_that_redirects_to_another_descriptor(self):
        first_url = "https://example.invalid/repo.json"
        second_url = "https://example.invalid/repo2.json"
        third_url = "https://example.invalid/v2/index.pb"
        first_descriptor = json.dumps({"index_v2": second_url}).encode("utf-8")
        second_descriptor = json.dumps({"index_v2": third_url}).encode("utf-8")
        fake, calls = patched_fetch({first_url: first_descriptor, second_url: second_descriptor})
        stderr = io.StringIO()
        with patch.object(compat_job, "fetch", side_effect=fake):
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.resolve_index(first_url)
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("redirects to another descriptor", stderr.getvalue())
        self.assertEqual(calls, [first_url, second_url])

    def test_refuses_a_legacy_flat_list_body(self):
        url = "https://example.invalid/index.min.json"
        fake, calls = patched_fetch({url: b"[]"})
        stderr = io.StringIO()
        with patch.object(compat_job, "fetch", side_effect=fake):
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.resolve_index(url)
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("legacy flat index.min.json", stderr.getvalue())
        self.assertEqual(calls, [url])

    def test_refuses_a_descriptor_with_no_index_v2(self):
        url = "https://example.invalid/repo.json"
        descriptor = json.dumps({"meta": {"signingKeyFingerprint": "deadbeef"}}).encode("utf-8")
        fake, calls = patched_fetch({url: descriptor})
        stderr = io.StringIO()
        with patch.object(compat_job, "fetch", side_effect=fake):
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.resolve_index(url)
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("no index_v2", stderr.getvalue())
        self.assertEqual(calls, [url])


# --- synthetic dex assembly ---------------------------------------------------------------------
#
# The reader under test walks class_defs to type_ids to string_ids to the descriptor, so the
# fixture has to be a real dex layout rather than a stub: a header with the six offsets at the
# addresses the format fixes, then the three tables and the string data they point into. Building
# it here keeps the test free of a device, a build and dexdump, which is the same standard-library
# constraint the job itself works under.

DEX_MAGIC = b"dex\n035\x00"
DEX_HEADER_SIZE = 0x70
CLASS_DEF_ITEM_SIZE = 32
ENDIAN_TAG = 0x12345678
NO_INDEX = 0xFFFFFFFF
CLASS_DEFS_OFF_FIELD = 0x64
STRING_IDS_OFF_FIELD = 0x3C


def build_dex(class_names: list[str]) -> bytes:
    """A dex defining exactly `class_names`, one class_def and one string per name."""
    descriptors = ["L" + name.replace(".", "/") + ";" for name in class_names]
    count = len(descriptors)
    string_ids_off = DEX_HEADER_SIZE
    type_ids_off = string_ids_off + 4 * count
    class_defs_off = type_ids_off + 4 * count
    data_off = class_defs_off + CLASS_DEF_ITEM_SIZE * count

    string_ids = b""
    data = b""
    for descriptor in descriptors:
        string_ids += struct.pack("<I", data_off + len(data))
        # A string_data_item is a ULEB128 length in UTF-16 code units, then MUTF-8 bytes, then a
        # NUL. The descriptors here are ASCII, so the code unit count is the byte count, and a
        # descriptor over 127 characters is what pushes the length into a second ULEB128 byte.
        data += encode_varint(len(descriptor)) + descriptor.encode("utf-8") + b"\x00"
    # Each type_id is an index into string_ids and each class_def opens with an index into
    # type_ids, so name i is reached through entry i of both tables.
    type_ids = b"".join(struct.pack("<I", index) for index in range(count))
    # class_def_item: class_idx, access_flags, superclass_idx, interfaces_off, source_file_idx,
    # annotations_off, class_data_off, static_values_off. Only the first is read.
    class_defs = b"".join(
        struct.pack("<8I", index, 0x1, NO_INDEX, 0, NO_INDEX, 0, 0, 0)
        for index in range(count)
    )

    body = string_ids + type_ids + class_defs + data
    header = DEX_MAGIC + struct.pack("<I", 0) + b"\x00" * 20 + struct.pack(
        "<20I",
        DEX_HEADER_SIZE + len(body),  # file_size
        DEX_HEADER_SIZE,              # header_size
        ENDIAN_TAG,                   # endian_tag
        0, 0,                         # link_size, link_off
        0,                            # map_off
        count, string_ids_off,
        count, type_ids_off,
        0, 0,                         # proto_ids_size, proto_ids_off
        0, 0,                         # field_ids_size, field_ids_off
        0, 0,                         # method_ids_size, method_ids_off
        count, class_defs_off,
        len(data), data_off,
    )
    assert len(header) == DEX_HEADER_SIZE, f"header is {len(header)} bytes, not {DEX_HEADER_SIZE}"
    return header + body


ALPHA = "com.shinsaikou.compat.Alpha"
BETA = "com.shinsaikou.compat.Beta$Companion"
GAMMA = "com.shinsaikou.compat.Gamma"


class DexClassNamesTest(unittest.TestCase):

    def test_reads_every_class_a_two_class_dex_defines(self):
        names = compat_job.dex_class_names(build_dex([ALPHA, BETA]), "classes.dex")
        self.assertEqual(names, {ALPHA, BETA})

    def test_reads_a_descriptor_whose_uleb128_length_needs_two_bytes(self):
        # 140 characters of package, so the descriptor is past 127 and its length no longer fits
        # in one ULEB128 byte. A reader that assumed one byte would start the name mid-descriptor.
        long_name = "com.shinsaikou.compat." + "a" * 140 + ".Held"
        self.assertGreater(len("L" + long_name.replace(".", "/") + ";"), 127)
        names = compat_job.dex_class_names(build_dex([ALPHA, long_name]), "classes.dex")
        self.assertEqual(names, {ALPHA, long_name})

    def test_skips_a_class_def_whose_type_index_is_out_of_range(self):
        # One entry the file can no longer explain costs that entry, not the run. class_defs_off
        # sits at 0x64, and class_idx is the first word of the item. This is NOT the truncation
        # case: see the next test for what a real cut does, which is refuse the whole file.
        dex = bytearray(build_dex([ALPHA, BETA]))
        class_defs_off = struct.unpack_from("<I", dex, CLASS_DEFS_OFF_FIELD)[0]
        struct.pack_into("<I", dex, class_defs_off, 99)
        self.assertEqual(compat_job.dex_class_names(bytes(dex), "classes.dex"), {BETA})

    def test_refuses_a_truncated_dex_rather_than_reporting_the_names_that_survived(self):
        # The honest behaviour, and the one the reader actually has. A cut lands in the string data
        # the tables point into, and a reader that answered with the names that happened to survive
        # would report every extension naming a lost class as a compatibility gap. Every prefix
        # that damages a table has to be refused by name, and the loop's per-entry skip above must
        # not quietly rescue it.
        whole = build_dex([ALPHA, BETA])
        refused = 0
        for cut in range(len(whole) - 1, DEX_HEADER_SIZE, -1):
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                try:
                    compat_job.dex_class_names(whole[:cut], "app-release.apk!classes.dex")
                except SystemExit as exit_code:
                    self.assertEqual(exit_code.code, 2)
                    self.assertIn("app-release.apk!classes.dex", stderr.getvalue())
                    refused += 1
        self.assertGreater(refused, 0, "no prefix of a dex was refused, so the guards do nothing")

    def test_refuses_a_file_that_is_not_a_dex_with_a_reason_naming_it(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as cm:
                compat_job.dex_class_names(b"PK\x03\x04" + b"\x00" * 200, "app-release.apk!classes.dex")
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("is not a dex file", stderr.getvalue())
        self.assertIn("app-release.apk!classes.dex", stderr.getvalue())

    def test_refuses_a_file_too_short_to_hold_a_dex_header(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as cm:
                compat_job.dex_class_names(DEX_MAGIC + b"\x00" * 8, "classes.dex")
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("shorter than a dex header", stderr.getvalue())

    def refusal(self, dex: bytes) -> str:
        """Runs the reader on `dex` and returns stderr, asserting it refused with exit code 2.

        Every case below feeds it a file that is long enough and carries the dex magic, so the two
        checks above pass it through to the walk. Before the table offsets were checked, four of
        the five cases below raised ValueError or IndexError out of the standard library and the
        fifth answered with a plausible-looking set of names read out of the wrong bytes. A
        traceback names neither the APK nor the entry inside it, which is the one thing a reader
        of a multi-dex APK has to be able to say.
        """
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as cm:
                compat_job.dex_class_names(dex, "app-release.apk!classes.dex")
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("app-release.apk!classes.dex", stderr.getvalue())
        return stderr.getvalue()

    def test_refuses_a_dex_magic_followed_by_a_body_of_0xff(self):
        # Every header word reads as 0xffffffff, so each table claims four billion entries at an
        # offset four billion bytes in.
        message = self.refusal(DEX_MAGIC[:4] + b"\xff" * 200)
        self.assertIn("string_ids", message)
        self.assertIn("does not fit inside its 204 bytes", message)

    def test_refuses_a_dex_magic_followed_by_a_body_of_0x01(self):
        # The other shape of garbage: offsets small enough to look plausible and still outside a
        # 204 byte file. The refusal has to come from the bounds check, not from a lucky read.
        message = self.refusal(DEX_MAGIC[:4] + b"\x01" * 200)
        self.assertIn("string_ids", message)

    def test_refuses_a_header_whose_class_defs_offset_is_past_the_end_of_the_file(self):
        dex = bytearray(build_dex([ALPHA, BETA]))
        struct.pack_into("<I", dex, CLASS_DEFS_OFF_FIELD, len(dex) + 4096)
        message = self.refusal(bytes(dex))
        self.assertIn("class_defs", message)

    def test_refuses_a_string_id_pointing_past_the_end_of_the_file(self):
        # The string_ids table itself is inside the file, so only the entry read out of it is
        # wrong. That is the case the table bounds cannot catch and dex_string has to.
        dex = bytearray(build_dex([ALPHA]))
        string_ids_off = struct.unpack_from("<I", dex, STRING_IDS_OFF_FIELD)[0]
        struct.pack_into("<I", dex, string_ids_off, len(dex) + 64)
        message = self.refusal(bytes(dex))
        self.assertIn("past the end", message)

    def test_refuses_a_string_with_no_nul_terminator_before_the_end_of_the_file(self):
        # The string data is the last thing in the file build_dex writes, so overwriting the final
        # byte leaves the last descriptor running to the end with nothing to end it.
        dex = bytearray(build_dex([ALPHA]))
        self.assertEqual(dex[-1], 0, "build_dex should end the last string with its NUL")
        dex[-1] = ord("A")
        message = self.refusal(bytes(dex))
        self.assertIn("no NUL terminator", message)

    def test_reads_a_dex_that_defines_no_classes_as_an_empty_set(self):
        # Empty is a legal dex: the tables carry no entries, so nothing is refused here. What must
        # not happen is that it contributes silently to an APK index, which is what the refusal in
        # ApkClassIndexTest below covers.
        self.assertEqual(compat_job.dex_class_names(build_dex([]), "classes.dex"), set())


class ApkClassIndexTest(unittest.TestCase):

    def test_refuses_an_apk_it_cannot_open_with_a_reason_naming_it(self):
        # A build interrupted part way leaves a file that is an APK by name only. Without the guard
        # this is a bare zipfile.BadZipFile traceback naming neither the file nor the remedy, which
        # is the same failure the dex reader's own checks exist to avoid one layer down.
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-release.apk"
            apk.write_bytes(b"PK\x03\x04" + b"\x00" * 64)
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as cm:
                    compat_job.apk_class_index(apk)
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("app-release.apk", stderr.getvalue())
        self.assertIn("assembleRelease", stderr.getvalue())

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)

    def write_apk(self, entries: dict[str, bytes]) -> Path:
        path = self.directory / "app-release.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name, blob in entries.items():
                archive.writestr(name, blob)
        return path

    def test_unions_the_names_of_every_classes_dex_and_ignores_everything_else(self):
        apk = self.write_apk({
            "AndroidManifest.xml": b"\x03\x00\x08\x00 not a dex",
            "classes.dex": build_dex([ALPHA, BETA]),
            "classes2.dex": build_dex([GAMMA]),
            "resources.arsc": b"\x02\x00\x0c\x00 not a dex either",
            "res/drawable/icon.png": b"\x89PNG\r\n\x1a\n",
        })
        self.assertEqual(compat_job.apk_class_index(apk), {ALPHA, BETA, GAMMA})

    def test_refuses_an_apk_holding_no_classes_dex(self):
        apk = self.write_apk({"AndroidManifest.xml": b"\x03\x00\x08\x00"})
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as cm:
                compat_job.apk_class_index(apk)
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("holds no classes.dex", stderr.getvalue())

    def test_names_the_entry_when_one_of_several_dex_files_is_not_a_dex(self):
        apk = self.write_apk({
            "classes.dex": build_dex([ALPHA]),
            "classes2.dex": b"PK\x03\x04" + b"\x00" * 200,
        })
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as cm:
                compat_job.apk_class_index(apk)
        self.assertEqual(cm.exception.code, 2)
        # The entry, not just the file: an APK carries several dex files and "not a dex" without a
        # name is not something anyone can act on.
        self.assertIn("app-release.apk!classes2.dex", stderr.getvalue())

    def test_refuses_a_classes_dex_that_defines_no_classes(self):
        # Zero names out of a dex that parsed cleanly used to pass straight through. The union with
        # android.jar still holds thousands of names, so the run continued and the damage arrived
        # much later as a collapsed loadedRatio with thousands of unresolved classes, which names
        # the wrong cause entirely. The refusal names the entry that actually went wrong.
        apk = self.write_apk({
            "classes.dex": build_dex([ALPHA]),
            "classes2.dex": build_dex([]),
        })
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as cm:
                compat_job.apk_class_index(apk)
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("app-release.apk!classes2.dex", stderr.getvalue())
        self.assertIn("defines no classes", stderr.getvalue())

    def test_reads_class_entries_out_of_a_jar_and_ignores_the_rest(self):
        # The android.jar half of the index, which still arrives as compiled class entries.
        path = self.directory / "android.jar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("android/view/View.class", b"\xca\xfe\xba\xbe")
            archive.writestr("android/view/View$OnClickListener.class", b"\xca\xfe\xba\xbe")
            archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        self.assertEqual(
            compat_job.jar_class_index(path),
            {"android.view.View", "android.view.View$OnClickListener"},
        )


if __name__ == "__main__":
    unittest.main()
