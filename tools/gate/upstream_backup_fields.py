#!/usr/bin/env python3
"""Records every serialized field of upstream v0.20.4's backup models, read from its Kotlin sources.

Unit R2 of design/plans/phase3-start.md, part 1. A `.tachibk` is stock protobuf over nine model files
under upstream's app/src/main/java/eu/kanade/tachiyomi/data/backup/models/, and Saikou's `:backup`
vendors the same classes under the same names, so the JVM cannot load upstream's copies beside
Saikou's (and the reference tree's backup slice does not build on its own). This reads the reference
tree as text instead and writes tools/gate/upstream_backup_fields.json: for each `@Serializable` class,
its serial name, whether it is sealed or extends a sealed class, and every constructor property in
declaration order with its proto number (from `@ProtoNumber`, or its position when there is none,
which is how kotlinx.serialization numbers an unannotated field), its name, its Kotlin type as
written, its nullability and its default as written (null when it has none). It also records the
entry order of `UpdateStrategy`, which the format encodes by ordinal.

:backup's UpstreamBackupFieldsTest holds Saikou's model descriptors and declarations against the file
in both directions. The file is generated once; with --check this regenerates it in memory and fails
when the committed file and the tree disagree, naming each difference.

The tree is --upstream, else SAIKOU_UPSTREAM_DIR, else `saikou.upstream.dir` in local.properties. A
source shape this does not understand (a constructor parameter that is not a property, an annotation
it cannot close) is an input error rather than a field quietly left out.

Exit codes: 0 written, or the file agrees with the tree; 1 --check found a disagreement; 2 no tree,
a model file missing from it, or a source this cannot read. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

AGREES, DISAGREES, BAD_INPUT = 0, 1, 2
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = Path(__file__).with_name("upstream_backup_fields.json")
MODELS_DIR = "app/src/main/java/eu/kanade/tachiyomi/data/backup/models"
ENUM_FILE = "source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/UpdateStrategy.kt"
ENUM_NAME = "UpdateStrategy"
REFERENCE = "Upstream v0.20.4"
COMMENT = (
    "Every serialized field of the reference tree's backup models and the entry order of UpdateStrategy, "
    "read from its Kotlin sources by tools/gate/upstream_backup_fields.py; regenerate with that script, never "
    "by hand. number is the proto number, numberedBy says whether it came from @ProtoNumber or from the "
    "field's position; type is as written without a trailing ?, which nullable carries; default is the "
    "default as written, or null when the field has none. :backup's UpstreamBackupFieldsTest holds Saikou's "
    "vendored models against this file in both directions."
)


class Unreadable(Exception):
    """A source shape this parser does not understand, or an input that is not there."""


def strip_comments(text: str) -> str:
    """Kotlin source with its comments blanked out, string and character literals kept.

    Line comments become nothing up to the newline and block comments become one space, so line
    structure survives. A `//` inside a string (a URL in an annotation argument) is not a comment.
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"' and text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end < 0:
                raise Unreadable("an unterminated raw string")
            out.append(text[i:end + 3])
            i = end + 3
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            if j >= n:
                raise Unreadable("an unterminated string literal")
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise Unreadable("an unterminated block comment")
            out.append(" ")
            i = end + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


_OPEN, _CLOSE = "([{<", ")]}>"


def matching(text: str, start: int) -> int:
    """The index of the bracket that closes the one at `start`, skipping string literals."""
    depth, i = 0, start
    while i < len(text):
        c = text[i]
        if c in "\"'":
            j = i + 1
            while j < len(text) and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise Unreadable(f"no closing bracket for the one at offset {start}")


def split_top_level(text: str) -> list[str]:
    """Splits on commas outside brackets, generics and strings; drops a trailing empty piece."""
    pieces, depth, current, i = [], 0, [], 0
    while i < len(text):
        c = text[i]
        if c in "\"'":
            j = i + 1
            while j < len(text) and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            current.append(text[i:j + 1])
            i = j + 1
            continue
        if c in _OPEN:
            depth += 1
        elif c in _CLOSE:
            depth -= 1
        if c == "," and depth == 0:
            pieces.append("".join(current))
            current = []
        else:
            current.append(c)
        i += 1
    pieces.append("".join(current))
    return [piece.strip() for piece in pieces if piece.strip()]


_ANNOTATION = re.compile(r"@([\w.]+)")
_PROPERTY = re.compile(r"(?:(?:private|internal|public|override)\s+)*(val|var)\s+(\w+)\s*:\s*(.+)", re.DOTALL)


def normalize_type(text: str) -> str:
    return re.sub(r"\s+", "", text).replace(",", ", ")


def parse_parameter(text: str, position: int, where: str) -> dict:
    """One constructor parameter: its annotations stripped, the property's name, type and default."""
    number = None
    rest = text.strip()
    while rest.startswith("@"):
        match = _ANNOTATION.match(rest)
        if match is None:
            raise Unreadable(f"{where}: an annotation this cannot read in {text!r}")
        end = match.end()
        arguments = ""
        if end < len(rest) and rest[end] == "(":
            close = matching(rest, end)
            arguments = rest[end + 1:close]
            end = close + 1
        if match.group(1).split(".")[-1] == "ProtoNumber":
            if not re.fullmatch(r"\s*\d+\s*", arguments):
                raise Unreadable(f"{where}: @ProtoNumber({arguments}) is not a literal number")
            number = int(arguments)
        rest = rest[end:].strip()
    prop = _PROPERTY.fullmatch(rest)
    if prop is None:
        raise Unreadable(f"{where}: parameter {position} is not a val or var property: {text!r}")
    name, declared = prop.group(2), prop.group(3)
    pieces = split_on_default(declared)
    type_text, default = pieces[0], pieces[1] if len(pieces) > 1 else None
    kotlin_type = normalize_type(type_text)
    nullable = kotlin_type.endswith("?")
    return {
        "number": number if number is not None else position,
        "numberedBy": "annotation" if number is not None else "position",
        "name": name,
        "type": kotlin_type[:-1] if nullable else kotlin_type,
        "nullable": nullable,
        "default": re.sub(r"\s+", " ", default).strip() if default is not None else None,
    }


def split_on_default(declared: str) -> list[str]:
    """`Type = default` split at the first `=` outside brackets and strings."""
    depth, i = 0, 0
    while i < len(declared):
        c = declared[i]
        if c in "\"'":
            j = i + 1
            while j < len(declared) and declared[j] != c:
                j += 2 if declared[j] == "\\" else 1
            i = j + 1
            continue
        if c in _OPEN:
            depth += 1
        elif c in _CLOSE:
            depth -= 1
        elif c == "=" and depth == 0:
            return [declared[:i].strip(), declared[i + 1:].strip()]
        i += 1
    return [declared.strip()]


_CLASS = re.compile(r"@Serializable\b(?:\s*\([^)]*\))?((?:\s*@[\w.]+(?:\([^)]*\))?)*)\s*"
                    r"((?:(?:data|sealed|abstract|open|internal|public)\s+)*)class\s+(\w+)")
_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)


def parse_models(source: str, file_name: str) -> dict[str, dict]:
    """Every `@Serializable` class in one Kotlin file, keyed by class name, in file order."""
    text = strip_comments(source)
    package = _PACKAGE.search(text)
    if package is None:
        raise Unreadable(f"{file_name}: no package declaration")
    messages: dict[str, dict] = {}
    for match in _CLASS.finditer(text):
        modifiers, name = match.group(2).split(), match.group(3)
        where = f"{file_name}: {name}"
        after = match.end()
        fields: list[dict] = []
        rest = text[after:].lstrip()
        offset = len(text) - len(rest)
        if rest.startswith("("):
            close = matching(text, offset)
            parameters = split_top_level(text[offset + 1:close])
            fields = [parse_parameter(p, i + 1, where) for i, p in enumerate(parameters)]
            rest = text[close + 1:]
        parent = re.match(r"\s*:\s*(\w+)\s*\(", rest)
        messages[name] = {
            "file": file_name,
            "serialName": f"{package.group(1)}.{name}",
            "sealed": "sealed" in modifiers,
            "extends": parent.group(1) if parent else None,
            "fields": fields,
        }
    return messages


def parse_enum(source: str, name: str, file_name: str) -> list[str]:
    """The entry names of `enum class <name>`, in declaration order."""
    text = strip_comments(source)
    match = re.search(rf"enum\s+class\s+{re.escape(name)}\b[^{{]*\{{", text)
    if match is None:
        raise Unreadable(f"{file_name}: no enum class {name}")
    body = text[match.end():matching(text, match.end() - 1)]
    body = body.split(";", 1)[0]
    entries = split_top_level(body)
    for entry in entries:
        if not re.fullmatch(r"\w+", entry):
            raise Unreadable(f"{file_name}: enum entry {entry!r} carries a body or arguments this does not read")
    return entries


def tree_commit(tree: Path) -> str | None:
    """The reference tree's commit, when it is a git checkout."""
    try:
        result = subprocess.run(["git", "-C", str(tree), "rev-parse", "HEAD"], capture_output=True,
                                text=True, check=False)
    except OSError:
        return None
    commit = result.stdout.strip()
    return commit if result.returncode == 0 and re.fullmatch(r"[0-9a-f]{40}", commit) else None


def generate(tree: Path) -> dict:
    """The document upstream_backup_fields.json holds, built from the tree."""
    models = tree / MODELS_DIR
    if not models.is_dir():
        raise Unreadable(f"missing: {models}")
    files = sorted(path for path in models.iterdir() if path.suffix == ".kt")
    if not files:
        raise Unreadable(f"missing: no .kt file under {models}")
    messages: dict[str, dict] = {}
    for path in files:
        for name, message in parse_models(path.read_text(encoding="utf-8"), path.name).items():
            if name in messages:
                raise Unreadable(f"{path.name}: class {name} is declared in {messages[name]['file']} too")
            messages[name] = message
    enum_path = tree / ENUM_FILE
    if not enum_path.is_file():
        raise Unreadable(f"missing: {enum_path}")
    return {
        "schemaVersion": 1,
        "comment": COMMENT,
        "reference": {"name": REFERENCE, "commit": tree_commit(tree)},
        "modelsDir": MODELS_DIR,
        "files": [path.name for path in files],
        "messages": messages,
        "enums": {ENUM_NAME: {"file": ENUM_FILE,
                              "entries": parse_enum(enum_path.read_text(encoding="utf-8"), ENUM_NAME, ENUM_FILE)}},
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def differences(recorded_text: str, generated: dict) -> list[str]:
    """One line per disagreement between the committed file's text and the tree, most specific first."""
    recorded = json.loads(recorded_text)
    lines: list[str] = []
    for key in ("reference", "modelsDir", "files"):
        if recorded.get(key) != generated.get(key):
            lines.append(f"{key}: the file has {recorded.get(key)!r}, the tree {generated.get(key)!r}")
    old, new = recorded.get("messages", {}), generated.get("messages", {})
    for name in sorted(set(old) - set(new)):
        lines.append(f"message {name}: in the file, not in the tree")
    for name in sorted(set(new) - set(old)):
        lines.append(f"message {name}: in the tree, not in the file")
    for name in sorted(set(old) & set(new)):
        a, b = old[name], new[name]
        for key in ("file", "serialName", "sealed", "extends"):
            if a.get(key) != b.get(key):
                lines.append(f"message {name}: {key} is {a.get(key)!r} in the file, {b.get(key)!r} in the tree")
        fa = {f["name"]: f for f in a.get("fields", [])}
        fb = {f["name"]: f for f in b.get("fields", [])}
        for field in sorted(set(fa) - set(fb)):
            lines.append(f"{name}.{field}: in the file, not in the tree")
        for field in sorted(set(fb) - set(fa)):
            lines.append(f"{name}.{field}: in the tree, not in the file")
        for field in sorted(set(fa) & set(fb)):
            for key in ("number", "numberedBy", "type", "nullable", "default"):
                if fa[field].get(key) != fb[field].get(key):
                    lines.append(f"{name}.{field}: {key} is {fa[field].get(key)!r} in the file, "
                                 f"{fb[field].get(key)!r} in the tree")
        order_a = [f["name"] for f in a.get("fields", [])]
        order_b = [f["name"] for f in b.get("fields", [])]
        if set(order_a) == set(order_b) and order_a != order_b:
            lines.append(f"message {name}: declaration order is {order_a} in the file, {order_b} in the tree")
    old_enums, new_enums = recorded.get("enums", {}), generated.get("enums", {})
    for name in sorted(set(old_enums) | set(new_enums)):
        if old_enums.get(name) != new_enums.get(name):
            lines.append(f"enum {name}: {old_enums.get(name)!r} in the file, {new_enums.get(name)!r} in the tree")
    if not lines and recorded_text != render(generated):
        lines.append("the file is not what the script writes for this tree (formatting or a key this does not compare)")
    return lines


def local_property(key: str) -> str | None:
    path = Path(os.environ.get("SAIKOU_LOCAL_PROPERTIES") or ROOT / "local.properties")
    if not path.is_file():
        return None
    value = None
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(rf"\s*{re.escape(key)}\s*=\s*(.*)$", line)
        if match:
            value = match.group(1).strip()
    return value or None


def resolve_tree(argument: str | None) -> Path | None:
    """--upstream, else SAIKOU_UPSTREAM_DIR, else saikou.upstream.dir. Blank is absent (R29)."""
    for value in (argument, os.environ.get("SAIKOU_UPSTREAM_DIR"), local_property("saikou.upstream.dir")):
        if value and value.strip():
            return Path(value.strip())
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Record the reference tree's backup model fields.")
    parser.add_argument("--upstream", help="the reference tree; default SAIKOU_UPSTREAM_DIR, then saikou.upstream.dir")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="the JSON file to write or check")
    parser.add_argument("--check", action="store_true", help="fail when the file and the tree disagree")
    args = parser.parse_args(argv)

    tree = resolve_tree(args.upstream)
    if tree is None:
        print("missing: the reference tree (--upstream, SAIKOU_UPSTREAM_DIR or saikou.upstream.dir)", file=sys.stderr)
        return BAD_INPUT
    if not tree.is_dir():
        print(f"missing: the reference tree {tree}", file=sys.stderr)
        return BAD_INPUT
    try:
        generated = generate(tree)
    except (Unreadable, OSError, UnicodeDecodeError) as exc:
        print(f"cannot read the reference tree: {exc}", file=sys.stderr)
        return BAD_INPUT

    count = sum(len(message["fields"]) for message in generated["messages"].values())
    summary = f"{len(generated['messages'])} messages, {count} fields, {len(generated['files'])} files"
    if not args.check:
        args.out.write_text(render(generated), encoding="utf-8")
        print(f"    {summary} written to {args.out}")
        return AGREES

    if not args.out.is_file():
        print(f"missing: {args.out}", file=sys.stderr)
        return BAD_INPUT
    try:
        found = differences(args.out.read_text(encoding="utf-8"), generated)
    except (json.JSONDecodeError, UnicodeDecodeError, AttributeError) as exc:
        print(f"{args.out} is not the JSON this script writes: {exc}", file=sys.stderr)
        return BAD_INPUT
    if found:
        for line in found:
            print(f"    DIFF  {line}")
        sys.stdout.flush()
        print(f"    {args.out} disagrees with the tree at {tree} in {len(found)} place(s); regenerate it "
              "with tools/gate/upstream_backup_fields.py and review the change", file=sys.stderr)
        return DISAGREES
    print(f"    {args.out} agrees with the tree at {tree}: {summary}")
    return AGREES


if __name__ == "__main__":
    raise SystemExit(main())
