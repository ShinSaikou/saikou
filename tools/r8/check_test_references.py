#!/usr/bin/env python3
"""Resolves every member the release androidTest APK references against the release APK.

AGP minifies the androidTest APK with the application's own mapping and leaves out whatever the
tested variant already carries. The consequence is that a member R8 removed because nothing the
shipped application calls is removed for the test as well, and the call fails at RUN TIME with
NoSuchMethodError, NoSuchFieldError or ClassNotFoundException. Nothing fails at build time, so the
only way to find these before an emulator does is to read both APKs and resolve the test's
references against the application's definitions, which is what this script does.

R8 does not only delete, it rewrites, and each rewrite leaves a hole of a different shape:

  - A property whose accessor no shipped call site uses is read as a field instead and the accessor
    goes, so the getter the instrumentation calls is missing by name.
  - An enum constant no code names literally goes, so a field read fails.
  - A file whose only function is unused goes whole, so the synthetic `...Kt` facade is missing.
  - A suspend function whose callers all pass the same continuation subclass has its parameter type
    narrowed to that subclass, so the method still exists but under a signature nothing outside the
    application can call.

Those four arrive here the same way: as a reference in the androidTest APK that resolves to no
definition in either APK. There is a fifth, and it does not:

  - A method that never reads `this` is made static. The member keeps its name and its descriptor,
    so it resolves perfectly well, and the call site still carries `invoke-virtual`. The device
    answers with IncompatibleClassChangeError instead of NoSuchMethodError.

That one is caught by comparing the opcode against the declaration rather than by resolution, in
check_invoke_kind below, and it is the reason this script reads dexdump's `access` lines. It was
found the expensive way: a run of this script reported nothing, and the instrumentation it cleared
then failed on the device.

THE BLIND SPOT. Resolution walks the superclass chain and the interfaces of the reference's owner.
That walk can only finish when every type on it is defined by one of the two APKs. java.lang.Object
is the one exception, because its members are fixed and are listed below, so ordinary chains
terminate cleanly. Any other type the walk needs and neither APK defines leaves the answer unknown,
and this script reports it as UNVERIFIABLE rather than as absent: a class whose definition is on the
device (android.app.Application, say) may well declare the member, and a class that is gone outright
may have declared it before R8 removed it. This is a real hole and not a formality. It is how a
missing `com.shinsaikou.SaikouApp.$stable` hid once, because SaikouApp extends a platform class and a class
extending a platform class cannot be decided this way. Read the UNVERIFIABLE list, do not skim past
it.

Usage:

    tools/r8/check_test_references.py <app apk> <androidTest apk> [--test-class <fqcn>]...
    tools/r8/check_test_references.py <app apk> <androidTest apk> --test-class <fqcn> --keeps

`--test-class` restricts the check to the named class and the nested classes the compiler emitted
beside it, and may be repeated. It defaults to every class the androidTest APK defines. Phase 0
step 7 passes `com.shinsaikou.extensions.ExtensionHostTest`, because that is the only class step 7 runs,
and generating keeps for suites step 7 never runs would put rules in the shipped configuration for
nothing. A suite that does its work through a helper class of its own has to name that helper as a
second `--test-class`, which is why the option repeats; see sources_for below for why the set is not
computed by following references instead.

`--keeps` prints ready to paste -keepclassmembers and -keep rules for what it found, which is how
the block in app/proguard-rules.pro is regenerated. A rule has to spell the name R8 matches, which
is the name before obfuscation, so this mode reads the mapping file the release build wrote. Member
names in the androidTest APK are usually already the original ones (a member R8 removed has no
mapping entry, so nothing renamed it), but a member R8 rewrote rather than removed is renamed like
any other, and the classes around both are renamed as well.

External programs: dexdump, which ships in the SDK build-tools beside apksigner. Python 3.11+,
standard library only.

Exit codes: 0 nothing absent; 1 something is absent (the list is on stdout); 2 missing or invalid
input; 4 dexdump was not found.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MAPPING = REPO_ROOT / "app" / "build" / "outputs" / "mapping" / "release" / "mapping.txt"

# The packages the device provides. A reference whose owner is one of these is not this script's
# business: the class comes from the boot classpath and R8 never touched it. androidx is absent on
# purpose, because androidx ships inside the APK and is shrunk with everything else. The prefixes
# are compared against dex descriptors, so `Landroid/` does not match `Landroidx/`: the slash is
# part of the prefix and androidx has an `x` in that position.
PLATFORM_PREFIXES = (
    "Ljava/",
    "Ljavax/",
    "Landroid/",
    "Ldalvik/",
    "Llibcore/",
    "Lsun/",
    "Lcom/sun/",
    "Lorg/w3c/dom/",
    "Lorg/xml/sax/",
    "Lorg/xmlpull/v1/",
    "Lorg/json/",
    "Lorg/apache/http/",
    "Lorg/apache/harmony/",
)

# java.lang.Object is the one platform class whose members are enumerated here rather than treated
# as unknowable. Every superclass chain ends at it, so without this list every single reference
# would come back UNVERIFIABLE and the script would report nothing at all.
OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
OBJECT_METHODS = frozenset({
    ("<init>", "()V"),
    ("clone", "()Ljava/lang/Object;"),
    ("equals", "(Ljava/lang/Object;)Z"),
    ("finalize", "()V"),
    ("getClass", "()Ljava/lang/Class;"),
    ("hashCode", "()I"),
    ("notify", "()V"),
    ("notifyAll", "()V"),
    ("toString", "()Ljava/lang/String;"),
    ("wait", "()V"),
    ("wait", "(J)V"),
    ("wait", "(JI)V"),
})

FOUND = "found"
ABSENT = "absent"
UNVERIFIABLE = "unverifiable"

PRIMITIVE_TYPES = {
    "V": "void", "Z": "boolean", "B": "byte", "S": "short",
    "C": "char", "I": "int", "J": "long", "F": "float", "D": "double",
}


def die(code: int, message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


# --- reading dexdump -d ---------------------------------------------------------------------------

CLASS_DESCRIPTOR = re.compile(r"^  Class descriptor  : '(\S+)'")
SUPERCLASS = re.compile(r"^  Superclass        : '(\S+)'")
SECTION = re.compile(r"^  (Interfaces|Static fields|Instance fields|Direct methods|Virtual methods) +-\s*$")
ENTRY = re.compile(r"^    #\d+ +: (.*)$")
NAME_LINE = re.compile(r"^      name          : '(.*)'$")
TYPE_LINE = re.compile(r"^      type          : '(.*)'$")
# Written by dexdump on the line after `type`, for both fields and methods. It is read for one flag
# only: R8 makes a method that never reads `this` static, and a member rewritten that way survives
# shrinking under its own name and descriptor, so nothing that matches on name and descriptor alone
# can see the change. See INVOKE_EXPECTS_STATIC below for what goes wrong when it is not read.
ACCESS_LINE = re.compile(r"^      access +: 0x[0-9a-f]+ \(([^)]*)\)$")
# A member reference as dexdump writes it inside an instruction: the owner in descriptor form, the
# member name, the member's type or the method's signature, then the pool index that says which of
# the two kinds it is.
MEMBER_REF = re.compile(r"(L[^\s;]+;)\.([^\s:]+):(\S+) // (method|field)@")
# The opcode that makes the reference, captured so the check can compare HOW a member is reached
# against how it is declared. `invoke-static` and the `sget`/`sput` family expect a static member;
# `invoke-virtual`, `invoke-interface`, `invoke-super`, `invoke-direct` and the `iget`/`iput` family
# expect an instance one. The `/range` and typed suffixes (`-wide`, `-object`, `-boolean` and the
# rest) do not change which of the two it is, so the pattern stops at the stem.
OPCODE = re.compile(r"\b(invoke-\w+|[is](?:get|put)\w*)\b")
INVOKE_EXPECTS_STATIC = {
    "invoke-static": True,
    "invoke-virtual": False,
    "invoke-interface": False,
    "invoke-super": False,
    "invoke-direct": False,
}
# A type reference: new-instance, check-cast, const-class, instance-of, new-array and the two
# filled-new-array forms all write the type the same way. Arrays of primitives do not start with an
# L and are left out by the pattern itself, which is correct: there is no class behind them.
TYPE_REF = re.compile(r"(\[*L[^\s;]+;) // type@")

FIELD_SECTIONS = ("Static fields", "Instance fields")
METHOD_SECTIONS = ("Direct methods", "Virtual methods")


@dataclass
class ClassDef:
    descriptor: str
    superclass: str | None = None
    interfaces: list[str] = field(default_factory=list)
    fields: set[tuple[str, str]] = field(default_factory=set)
    methods: set[tuple[str, str]] = field(default_factory=set)
    # The subset of the two sets above that carries ACC_STATIC. Kept beside them rather than folded
    # into them so that every existing lookup stays a plain membership test on (name, type).
    static_fields: set[tuple[str, str]] = field(default_factory=set)
    static_methods: set[tuple[str, str]] = field(default_factory=set)

    def is_static(self, kind: str, member: tuple[str, str]) -> bool:
        return member in (self.static_methods if kind == "method" else self.static_fields)


@dataclass(frozen=True)
class Reference:
    kind: str           # "method", "field" or "type"
    owner: str          # descriptor of the class the reference names
    name: str           # empty for a "type" reference
    type: str           # the field's type, the method's signature, empty for a "type" reference
    source: str         # descriptor of the class whose code holds the reference
    opcode: str = ""    # the instruction that makes it, empty for a "type" reference

    @property
    def expects_static(self) -> bool | None:
        """Whether this reference's opcode requires a static member, or None when it cannot say."""
        if self.kind == "method":
            return INVOKE_EXPECTS_STATIC.get(self.opcode.split("/")[0])
        if self.kind == "field" and self.opcode:
            return self.opcode[0] == "s"
        return None


def parse_dexdump(text: str, collect_references: bool = True) -> tuple[dict[str, ClassDef], set[Reference]]:
    """Reads one `dexdump -d` dump into its class definitions and, optionally, its references.

    Definitions are what the application APK is read for and references are what the androidTest APK
    is read for, so the second half is switchable: the application's dump runs to five million lines
    and matching two regexes against every instruction in it would cost a minute for an answer
    nothing reads.
    """
    classes: dict[str, ClassDef] = {}
    references: set[Reference] = set()
    current: ClassDef | None = None
    section: str | None = None
    pending_name: str | None = None
    pending_member: tuple[str, str] | None = None

    for line in text.splitlines():
        if not line:
            continue
        # Instruction lines start at column zero with the offset of the instruction; every line that
        # describes the shape of a class is indented. Testing the first character before running any
        # regex is what keeps this loop affordable on a dump of five million lines.
        if not line[0].isspace():
            if collect_references and current is not None and "@" in line:
                found = MEMBER_REF.findall(line)
                # One instruction makes at most one member reference, so the single opcode on the
                # line belongs to it. Matched only when there is something to attach it to.
                opcode_match = OPCODE.search(line) if found else None
                opcode = opcode_match.group(1) if opcode_match else ""
                for owner, name, member_type, kind in found:
                    references.add(
                        Reference(kind, owner, name, member_type, current.descriptor, opcode))
                for descriptor in TYPE_REF.findall(line):
                    references.add(Reference("type", descriptor.lstrip("["), "", "", current.descriptor))
            continue

        match = CLASS_DESCRIPTOR.match(line)
        if match:
            current = ClassDef(match.group(1))
            classes[current.descriptor] = current
            section = None
            pending_name = None
            pending_member = None
            continue
        if current is None:
            continue
        match = SUPERCLASS.match(line)
        if match:
            # java.lang.Object itself has no superclass, and dexdump writes that as the literal
            # string below rather than by leaving the line out.
            current.superclass = None if match.group(1) == "(none)" else match.group(1)
            continue
        match = SECTION.match(line)
        if match:
            section = match.group(1)
            pending_name = None
            pending_member = None
            continue
        match = ENTRY.match(line)
        if match:
            if section == "Interfaces":
                current.interfaces.append(match.group(1).strip().strip("'"))
            pending_name = None
            pending_member = None
            continue
        match = NAME_LINE.match(line)
        if match:
            pending_name = match.group(1)
            continue
        match = TYPE_LINE.match(line)
        if match and pending_name is not None:
            # dexdump always writes `name` before `type`, so the pair is complete here and the
            # member can be filed without waiting for the next entry to close this one. The `access`
            # line that follows is read separately, and a dump that omits it costs the staticness
            # check rather than the member.
            member = (pending_name, match.group(1))
            if section in FIELD_SECTIONS:
                current.fields.add(member)
            elif section in METHOD_SECTIONS:
                current.methods.add(member)
            pending_name = None
            pending_member = member
            continue
        match = ACCESS_LINE.match(line)
        if match and pending_member is not None:
            if "STATIC" in match.group(1).split():
                if section in FIELD_SECTIONS:
                    current.static_fields.add(pending_member)
                elif section in METHOD_SECTIONS:
                    current.static_methods.add(pending_member)
            pending_member = None
            continue
    return classes, references


# --- locating dexdump and dumping an APK ------------------------------------------------------------

def local_properties() -> dict[str, str]:
    path = Path(os.environ.get("SAIKOU_LOCAL_PROPERTIES", REPO_ROOT / "local.properties"))
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def sdk_root() -> Path | None:
    """The environment wins and local.properties is the fallback, the order compat_job.py uses."""
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name)
        if value:
            return Path(value)
    value = local_properties().get("sdk.dir")
    return Path(value) if value else None


def build_tools_dexdump(root: Path) -> Path | None:
    """The newest installed build-tools `dexdump`. Point releases sort correctly as strings.

    The version is found rather than named for the same reason tools/compat/compat_job.py finds
    apksigner rather than naming it: the pinned build-tools version moves with AGP, and a script
    that hardcodes one starts failing on a machine that has only a newer one.
    """
    build_tools = root / "build-tools"
    candidates = sorted(build_tools.glob("*/dexdump"), reverse=True) if build_tools.is_dir() else []
    return next((c for c in candidates if c.is_file()), None)


def dump_apk(dexdump: Path, apk: Path, collect_references: bool) -> tuple[dict[str, ClassDef], set[Reference]]:
    """Unzips every classes*.dex out of one APK and parses the disassembly of each."""
    classes: dict[str, ClassDef] = {}
    references: set[Reference] = set()
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        with zipfile.ZipFile(apk) as archive:
            names = [n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            if not names:
                die(2, f"{apk} holds no classes*.dex")
            for name in sorted(names):
                archive.extract(name, work)
        for dex in sorted(work.glob("classes*.dex")):
            # errors="replace" rather than the default: dexdump echoes every string constant in the
            # dex, and one constant holding bytes that are not UTF-8 would otherwise abort the whole
            # run on a decode error inside something no rule here reads.
            result = subprocess.run(
                [str(dexdump), "-d", str(dex)],
                capture_output=True, text=True, errors="replace", check=False,
            )
            if result.returncode != 0:
                die(2, f"dexdump failed on {dex} from {apk}:\n{result.stderr.strip()}")
            dex_classes, dex_references = parse_dexdump(result.stdout, collect_references)
            classes.update(dex_classes)
            references |= dex_references
    return classes, references


# --- resolution -------------------------------------------------------------------------------------

def is_platform(descriptor: str) -> bool:
    return descriptor.startswith(PLATFORM_PREFIXES)


def resolve(definitions: dict[str, ClassDef], reference: Reference) -> tuple[str, ClassDef | None]:
    """FOUND, ABSENT or UNVERIFIABLE for one member reference, with the class that declared it.

    The walk is the one the runtime performs: the owner itself, then its superclasses, then its
    interfaces, because an inherited method and a constant declared on an interface are both reached
    through the reference's owner rather than through their declaring type. It stops being able to
    answer the moment it needs a class neither APK defines, and says so rather than guessing.

    The declaring class comes back with the verdict because FOUND is not the end of the question:
    see check_invoke_kind. It is None when the answer is not FOUND, and also for the java.lang.Object
    members listed above, which are declared by a class this script never reads.
    """
    incomplete = False
    seen: set[str] = set()
    stack = [reference.owner]
    while stack:
        descriptor = stack.pop()
        if descriptor in seen:
            continue
        seen.add(descriptor)
        if descriptor == OBJECT_DESCRIPTOR:
            if reference.kind == "method" and (reference.name, reference.type) in OBJECT_METHODS:
                return FOUND, None
            continue
        definition = definitions.get(descriptor)
        if definition is None:
            # Either a platform class, whose members cannot be enumerated from here, or a class that
            # is simply gone. Both leave the same hole in the answer.
            incomplete = True
            continue
        members = definition.methods if reference.kind == "method" else definition.fields
        if (reference.name, reference.type) in members:
            return FOUND, definition
        if definition.superclass:
            stack.append(definition.superclass)
        stack.extend(definition.interfaces)
    return (UNVERIFIABLE if incomplete else ABSENT), None


def check_invoke_kind(reference: Reference, declaring: ClassDef | None) -> bool:
    """True when the reference reaches a member declared the way the reference expects.

    FOUND is not enough. R8 makes a method that never reads `this` static, and the member then
    survives shrinking under its own name and its own descriptor, so every check that matches on
    name and descriptor reports nothing wrong. The call site compiled against the unshrunk class
    still carries `invoke-virtual`, and the device answers it with

        IncompatibleClassChangeError: the method was expected to be of type virtual but instead was
        found to be of type static

    which is a fifth rewrite beyond the four in this module's docstring, and the one that cost this
    project a green release instrumentation run that then failed on a device. The fix is the same as
    for the others, a keep rule for the member, because keeping it is what stops the staticization.

    Returns True when the question cannot be asked: an unknown opcode, a member declared by a class
    outside both APKs, or a dump with no `access` lines to read.
    """
    expected = reference.expects_static
    if expected is None or declaring is None:
        return True
    member = (reference.name, reference.type)
    if member not in (declaring.methods if reference.kind == "method" else declaring.fields):
        return True
    # A class with no static member recorded at all is indistinguishable from a dump this parser
    # read no access lines out of, so the check declines rather than reporting every instance
    # member of it as a mismatch.
    if not (declaring.static_methods or declaring.static_fields) and expected is False:
        return True
    return declaring.is_static(reference.kind, member) == expected


@dataclass
class Findings:
    absent_members: list[Reference] = field(default_factory=list)
    absent_classes: list[str] = field(default_factory=list)
    unverifiable: list[Reference] = field(default_factory=list)
    # Present under the right name and descriptor, reached the wrong way. Counted as a failure with
    # the absent ones, because it fails on the device exactly as loudly.
    mismatched: list[Reference] = field(default_factory=list)

    def clean(self) -> bool:
        return not self.absent_members and not self.absent_classes and not self.mismatched


def sources_for(seeds: list[str], test_classes: dict[str, ClassDef]) -> set[str]:
    """A seed class plus the nested classes the compiler emitted beside it, and no further.

    A test class does most of its work from those nested classes rather than from its own body:
    every suspend function becomes a continuation class and every lambda becomes another class, and
    they hold the calls into the application. Stopping at the seed alone would check almost nothing.

    Following references instead, class by class, is the obvious generalisation and it does not
    work. R8 merges unrelated lambdas into one synthetic class that dispatches on a byte field, so a
    single lambda of the test's lands in a class that also holds lambdas from the Compose test
    runtime, and one hop through it reaches the entire androidTest APK. Measured on this repository
    that turns 39 findings into 657, almost all of them in library code no gate step runs. The
    nested classes are exactly the code the named test's own source produced, which is the set a
    keep rule should be written for. A suite whose work lives in a sibling helper class names that
    helper as a second --test-class.
    """
    sources: set[str] = set()
    for seed in seeds:
        stem = seed[:-1] + "$"
        sources.update(d for d in test_classes if d == seed or d.startswith(stem))
    return sources


def analyse(
    app_classes: dict[str, ClassDef],
    test_classes: dict[str, ClassDef],
    test_references: set[Reference],
    seeds: list[str] | None,
) -> Findings:
    definitions = dict(app_classes)
    definitions.update(test_classes)
    sources = set(test_classes) if seeds is None else sources_for(seeds, test_classes)

    absent_classes: set[str] = set()
    absent_members: set[Reference] = set()
    unverifiable: set[Reference] = set()
    mismatched: set[Reference] = set()
    for reference in test_references:
        if reference.source not in sources:
            continue
        if is_platform(reference.owner):
            continue
        if reference.owner not in definitions:
            absent_classes.add(reference.owner)
            continue
        if reference.kind == "type":
            continue
        # The source class is dropped from the identity here on purpose: the same call made from
        # three continuations is one missing member and deserves one rule, not three.
        anonymous = Reference(
            reference.kind, reference.owner, reference.name, reference.type, "", reference.opcode)
        verdict, declaring = resolve(definitions, reference)
        if verdict == ABSENT:
            absent_members.add(anonymous)
        elif verdict == UNVERIFIABLE:
            unverifiable.add(anonymous)
        elif not check_invoke_kind(reference, declaring):
            mismatched.add(anonymous)
    order = lambda r: (r.owner, r.name, r.type)
    return Findings(
        absent_members=sorted(absent_members, key=order),
        absent_classes=sorted(absent_classes),
        unverifiable=sorted(unverifiable, key=order),
        mismatched=sorted(mismatched, key=order),
    )


# --- names --------------------------------------------------------------------------------------------

def dotted(descriptor: str) -> str:
    return descriptor[1:-1].replace("/", ".")


# A member line of mapping.txt, with the line-number ranges R8 puts around it when the member has
# them. The name is captured separately from the return type so that an inlined frame, which writes
# the name as `package.Class.method` because the frame belongs to another class, can be recognised
# by the dot it carries and dropped.
MAPPING_MEMBER = re.compile(r"^ +(?:\d+:\d+:)?(\S+) ([^\s(]+)(\([^)]*\))?(?::\d+(?::\d+)?)? -> (\S+)$")


@dataclass(frozen=True)
class MappedMember:
    """One `original -> obfuscated` member line, kept in the original's spelling.

    `parameters` is None for a field. The strings are exactly what mapping.txt wrote, which is
    already the dotted, unobfuscated form a keep rule wants, so nothing here has to be converted.
    """
    name: str
    type: str
    parameters: tuple[str, ...] | None


@dataclass
class Mapping:
    classes: dict[str, list[str]] = field(default_factory=dict)
    members: dict[str, dict[str, list[MappedMember]]] = field(default_factory=dict)

    def original_class(self, name: str) -> str:
        names = self.classes.get(name)
        return names[0] if names else name

    def alternatives(self, name: str) -> list[str]:
        names = self.classes.get(name, [])
        return names if len(names) > 1 else []


def read_mapping(path: Path) -> Mapping:
    """Reads mapping.txt into obfuscated-to-original names for classes and for their members.

    Both halves are needed and for different reasons. A class the instrumentation still reaches was
    kept but renamed, so its rule has to be written with the name it had in the source. A member R8
    rewrote rather than removed was renamed too, and a rule naming the one-letter name it now has
    would match a member called that in the source and keep nothing. A member R8 removed outright
    has no line here at all, which is why every lookup falls back to the name the androidTest APK
    carries.

    The class list is a list rather than one name because class merging can put two original classes
    behind one obfuscated name. A rule written from an ambiguous name would be a guess, so the
    alternatives are carried into the output instead of being dropped silently.
    """
    mapping = Mapping()
    if not path.is_file():
        return mapping
    current: dict[str, list[MappedMember]] | None = None
    with path.open(errors="ignore") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.lstrip().startswith("#"):
                continue
            if not line[0].isspace():
                if " -> " not in line or not line.endswith(":"):
                    current = None
                    continue
                source, _, target = line.partition(" -> ")
                target = target[:-1].strip()
                mapping.classes.setdefault(target, []).append(source.strip())
                current = mapping.members.setdefault(target, {})
                continue
            if current is None:
                continue
            match = MAPPING_MEMBER.match(line)
            if not match:
                continue
            member_type, name, parameters, obfuscated = match.groups()
            if "." in name:
                # An inlined frame: the line records that a method of another class was inlined into
                # this one, not that this class has a member by that name.
                continue
            arguments = None
            if parameters is not None:
                inner = parameters[1:-1].strip()
                arguments = tuple(p.strip() for p in inner.split(",")) if inner else ()
            member = MappedMember(name, member_type, arguments)
            entries = current.setdefault(obfuscated, [])
            if member not in entries:
                entries.append(member)
    return mapping


def java_type(descriptor: str, mapping: Mapping) -> str:
    depth = 0
    while descriptor.startswith("["):
        depth += 1
        descriptor = descriptor[1:]
    if descriptor in PRIMITIVE_TYPES:
        base = PRIMITIVE_TYPES[descriptor]
    else:
        base = mapping.original_class(dotted(descriptor))
    return base + "[]" * depth


def split_signature(signature: str) -> tuple[list[str], str]:
    """`(Ljava/lang/String;I)V` into its parameter descriptors and its return descriptor."""
    close = signature.index(")")
    body, return_type = signature[1:close], signature[close + 1:]
    parameters: list[str] = []
    index = 0
    while index < len(body):
        start = index
        while body[index] == "[":
            index += 1
        if body[index] == "L":
            index = body.index(";", index) + 1
        else:
            index += 1
        parameters.append(body[start:index])
    return parameters, return_type


@dataclass(frozen=True)
class Rule:
    """One keep rule: the class whose braces it belongs in, the member line, and any ambiguity."""
    owner: str
    text: str
    alternatives: tuple[str, ...] = ()


def mapped_member(
    reference: Reference,
    mapping: Mapping,
    definitions: dict[str, ClassDef],
) -> tuple[str, MappedMember] | None:
    """The declaring class and the original spelling of a member R8 renamed, or None.

    The search walks the same hierarchy `resolve` walks, and it has to. A test calls a member
    through the static type it holds, while mapping.txt records the member against the type that
    declares it, so a call to CatalogueSource lands on a line filed under Source. The declaring
    class is returned along with the member because it is the class the rule has to name:
    `-keepclassmembers class CatalogueSource { ... }` matches members CatalogueSource declares, and
    keeps nothing at all when the member belongs to a supertype.

    Candidates are looked up by the name the androidTest APK carries, then narrowed by arity, which
    is the only part of a signature that survives both obfuscation and the parameter narrowing R8
    does to suspend functions. An overload set still ambiguous after that returns nothing, so the
    caller falls back to the reference's own spelling rather than emitting a rule for the wrong
    overload.
    """
    seen: set[str] = set()
    queue = [reference.owner]
    while queue:
        descriptor = queue.pop(0)
        if descriptor in seen:
            continue
        seen.add(descriptor)
        candidates = mapping.members.get(dotted(descriptor), {}).get(reference.name, [])
        if reference.kind == "field":
            candidates = [c for c in candidates if c.parameters is None]
        else:
            arity = len(split_signature(reference.type)[0])
            candidates = [c for c in candidates
                          if c.parameters is not None and len(c.parameters) == arity]
        if candidates:
            return (descriptor, candidates[0]) if len(candidates) == 1 else None
        definition = definitions.get(descriptor)
        if definition is None:
            continue
        if definition.superclass:
            queue.append(definition.superclass)
        queue.extend(definition.interfaces)
    return None


def rule_for(reference: Reference, mapping: Mapping, definitions: dict[str, ClassDef]) -> Rule:
    """One member as a keep rule writes it, in the names R8 matches against.

    The mapping is preferred where it has an answer because it holds the original signature as well
    as the original name, and the original signature is the whole point for a suspend function whose
    continuation parameter R8 narrowed: keeping the narrowed one would keep a method the
    instrumentation still cannot call. A member R8 removed outright has no mapping line, and there
    the androidTest APK's own spelling is already the original one.
    """
    found = mapped_member(reference, mapping, definitions)
    if found is not None:
        descriptor, mapped = found
        if mapped.parameters is None:
            text = f"{mapped.type} {mapped.name};"
        elif mapped.name == "<init>":
            text = f"<init>({', '.join(mapped.parameters)});"
        else:
            text = f"{mapped.type} {mapped.name}({', '.join(mapped.parameters)});"
    else:
        descriptor = reference.owner
        if reference.kind == "field":
            text = f"{java_type(reference.type, mapping)} {reference.name};"
        else:
            parameters, return_type = split_signature(reference.type)
            arguments = ", ".join(java_type(p, mapping) for p in parameters)
            if reference.name == "<init>":
                text = f"<init>({arguments});"
            else:
                text = f"{java_type(return_type, mapping)} {reference.name}({arguments});"
    name = dotted(descriptor)
    return Rule(mapping.original_class(name), text, tuple(mapping.alternatives(name)))


# --- output ---------------------------------------------------------------------------------------------

def print_report(findings: Findings, mapping: Mapping, definitions: dict[str, ClassDef]) -> None:
    print(f"absent members: {len(findings.absent_members)}")
    for reference in findings.absent_members:
        rule = rule_for(reference, mapping, definitions)
        print(f"  {rule.owner}: {rule.text}")
    print(f"absent classes: {len(findings.absent_classes)}")
    for descriptor in findings.absent_classes:
        print(f"  {mapping.original_class(dotted(descriptor))}")
    # Present under the right name and descriptor, reached the wrong way, which the device answers
    # with IncompatibleClassChangeError rather than NoSuchMethodError. The opcode is printed because
    # it is the whole finding: the member is there, and the way the test reaches it is not.
    print(f"wrong invoke kind: {len(findings.mismatched)}")
    for reference in findings.mismatched:
        rule = rule_for(reference, mapping, definitions)
        # What the call site does, then what it found, because the finding is the disagreement.
        found = "static" if reference.expects_static is False else "an instance member"
        print(f"  {rule.owner}: {rule.text}  ({reference.opcode} reaches it, R8 made it {found})")
    # Printed last and never counted as a failure. These are the references whose resolution walk
    # needed a class neither APK defines, so the answer is not "present" and not "absent" but
    # "undecidable from two APKs". The blind spot in this module's docstring is what they are.
    print(f"unverifiable: {len(findings.unverifiable)}")
    for reference in findings.unverifiable:
        rule = rule_for(reference, mapping, definitions)
        print(f"  {rule.owner}: {rule.text}")


def print_keeps(
    findings: Findings,
    mapping: Mapping,
    definitions: dict[str, ClassDef],
    command: str,
) -> None:
    print("# Generated by tools/r8/check_test_references.py. Regenerate with:")
    print(f"#   {command}")
    print("#")
    print("# Every rule below names a class or a member the instrumentation reaches and R8 removed")
    print("# or rewrote. Group them and write the reason for each group by hand before pasting.")
    for descriptor in findings.absent_classes:
        name = dotted(descriptor)
        for alternative in mapping.alternatives(name):
            print(f"# ambiguous, the mapping puts this class behind a shared name: {alternative}")
        print(f"-keep class {mapping.original_class(name)} {{")
        print("    *;")
        print("}")
    by_owner: dict[str, set[str]] = {}
    ambiguous: dict[str, tuple[str, ...]] = {}
    # A member R8 staticized needs the same rule as one R8 removed: keeping it is what stops both.
    for reference in findings.absent_members + findings.mismatched:
        rule = rule_for(reference, mapping, definitions)
        by_owner.setdefault(rule.owner, set()).add(rule.text)
        if rule.alternatives:
            ambiguous[rule.owner] = rule.alternatives
    for owner in sorted(by_owner):
        for alternative in ambiguous.get(owner, ()):
            print(f"# ambiguous, the mapping puts this class behind a shared name: {alternative}")
        print(f"-keepclassmembers class {owner} {{")
        for text in sorted(by_owner[owner]):
            print(f"    {text}")
        print("}")


# --- main -------------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(
        description="Resolves the androidTest APK's references against the release APK.")
    parser.add_argument("apk", type=Path, help="the release application APK")
    parser.add_argument("test_apk", type=Path, help="the release androidTest APK")
    parser.add_argument("--test-class", action="append", default=[], metavar="FQCN",
                        help="restrict the check to this test class and its nested classes; "
                             "repeatable, and defaults to every class the androidTest APK defines")
    parser.add_argument("--keeps", action="store_true",
                        help="print -keep rules for what was found instead of the report")
    parser.add_argument("--mapping", type=Path, default=DEFAULT_MAPPING,
                        help="the release mapping.txt, read to turn obfuscated names back into the "
                             "names a keep rule has to spell")
    args = parser.parse_args()

    for apk in (args.apk, args.test_apk):
        if not apk.is_file():
            die(2, f"missing: {apk}")

    root = sdk_root()
    if root is None:
        die(2, "missing: ANDROID_SDK_ROOT (or ANDROID_HOME, or sdk.dir in local.properties)")
    dexdump = build_tools_dexdump(root)
    if dexdump is None:
        die(4, f"missing: {root}/build-tools/*/dexdump "
               "(install it with sdkmanager 'build-tools;36.1.0')")

    app_classes, _ = dump_apk(dexdump, args.apk, collect_references=False)
    test_classes, test_references = dump_apk(dexdump, args.test_apk, collect_references=True)

    seeds: list[str] | None = None
    if args.test_class:
        seeds = ["L" + fqcn.replace(".", "/") + ";" for fqcn in args.test_class]
        unknown = [f for f, d in zip(args.test_class, seeds) if d not in test_classes]
        if unknown:
            die(2, f"{args.test_apk} defines no such class: {', '.join(unknown)}")

    findings = analyse(app_classes, test_classes, test_references, seeds)
    mapping = read_mapping(args.mapping)
    definitions = dict(app_classes)
    definitions.update(test_classes)

    if args.keeps:
        command = " ".join(["tools/r8/check_test_references.py", str(args.apk), str(args.test_apk)]
                           + [a for c in args.test_class for a in ("--test-class", c)] + ["--keeps"])
        print_keeps(findings, mapping, definitions, command)
    else:
        print_report(findings, mapping, definitions)
    return 0 if findings.clean() else 1


if __name__ == "__main__":
    raise SystemExit(main())
