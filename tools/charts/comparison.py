#!/usr/bin/env python3
"""Draws the README's benchmark comparison from comparison.json.

    python3 tools/charts/comparison.py <out-dir>

Writes comparison-light.svg and comparison-dark.svg (fixed colours, for the README's <picture>)
and comparison-inline.svg (colours from CSS classes, for a page that styles them itself).

Small multiples: one panel per measurement, each on its own scale from zero, because the panels
mix seconds and megabytes. Two series in fixed order (Saikou, then the other app), a legend, and the
value at every bar's tip. Standard library only.
"""

import json
import sys
from html import escape
from pathlib import Path

WIDTH = 720
COL_W, COL_GAP = 340, 40
PANEL_H = 112
TOP = 36
LABEL_W = 60
VALUE_W = 74
BAR_H = 18
FONT = "system-ui, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"

THEMES = {
    "light": {"text": "#0b0b0b", "muted": "#52514e", "rule": "#e4e3df", "s1": "#2a78d6", "s2": "#eb6834"},
    "dark": {"text": "#f0efec", "muted": "#c3c2b7", "rule": "#3a3a37", "s1": "#3987e5", "s2": "#d95926"},
}


def fmt(value, unit):
    if unit == "MB":
        return f"{value:,.0f} MB"
    return f"{value:.1f} s" if value >= 10 else f"{value:.2f} s"


def bar(x, y, w, h):
    """A bar square at the baseline with a 4 px rounded data end."""
    r = min(4, w / 2, h / 2)
    if w <= 0:
        return ""
    return (f"M{x:.1f},{y:.1f} h{w - r:.1f} a{r},{r} 0 0 1 {r},{r} v{h - 2 * r:.1f} "
            f"a{r},{r} 0 0 1 -{r},{r} h-{w - r:.1f} Z")


def wrap(text, width=112):
    lines, line = [], ""
    for word in text.split():
        if len(line) + len(word) + 1 > width and line:
            lines.append(line)
            line = word
        else:
            line = f"{line} {word}".strip()
    if line:
        lines.append(line)
    return lines


def render(data, theme):
    inline = theme is None
    c = THEMES["light"] if inline else theme

    def paint(role):
        # The inline variant leaves colour to the page's stylesheet.
        return f'class="cmp-{role}"' if inline else f'fill="{c[role]}"'

    def stroke(role):
        return f'class="cmp-{role}-line"' if inline else f'stroke="{c[role]}"'

    apps = data["apps"]
    panels = data["panels"]
    rows = (len(panels) + 1) // 2
    notes = []
    for note in data["notes"] + [f"Measured {data['measured']}. {data['setup']}"]:
        notes += wrap(note)
    height = TOP + rows * PANEL_H + 8 + len(notes) * 16

    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{height}" '
        f'viewBox="0 0 {WIDTH} {height}" role="img" aria-labelledby="cmp-title cmp-desc" '
        f'font-family="{FONT}">',
        f'<title id="cmp-title">{escape(apps["saikou"])} compared with {escape(apps["other"])}</title>',
    ]
    desc = []
    for p in panels:
        other = fmt(p["other"], p["unit"]) if p["other"] is not None else p.get("other_note", "no result")
        desc.append(f'{p["title"]} ({p["detail"]}): {apps["saikou"]} {fmt(p["saikou"], p["unit"])}, {apps["other"]} {other}.')
    out.append(f'<desc id="cmp-desc">{escape(" ".join(desc + data["notes"]))}</desc>')

    # Legend: always present for two series.
    x = 0
    for role, name in (("s1", apps["saikou"]), ("s2", apps["other"])):
        out.append(f'<rect x="{x}" y="4" width="12" height="12" rx="3" {paint(role)}/>')
        out.append(f'<text x="{x + 18}" y="14.5" font-size="13" {paint("text")}>{escape(name)}</text>')
        x += 18 + int(len(name) * 7.4) + 24

    for i, p in enumerate(panels):
        px = (i % 2) * (COL_W + COL_GAP)
        top = TOP + (i // 2) * PANEL_H
        values = [v for v in (p["saikou"], p["other"]) if v is not None]
        scale = (COL_W - LABEL_W - VALUE_W) / max(values)
        out.append(f'<text x="{px}" y="{top + 14}" font-size="13" font-weight="600" {paint("text")}>{escape(p["title"])}</text>')
        out.append(f'<text x="{px}" y="{top + 31}" font-size="12" {paint("muted")}>{escape(p["detail"])}</text>')
        base = px + LABEL_W
        out.append(f'<line x1="{base - 0.5}" y1="{top + 40}" x2="{base - 0.5}" y2="{top + 94}" stroke-width="1" {stroke("rule")}/>')
        for row, (key, role, name) in enumerate((("saikou", "s1", "Saikou"), ("other", "s2", apps["other"].split()[0]))):
            y = top + 44 + row * 28
            out.append(f'<text x="{px}" y="{y + 13}" font-size="12" {paint("muted")}>{escape(name)}</text>')
            value = p[key]
            if value is None:
                note = p.get("other_note", "No result")
                out.append(f'<text x="{base + 6}" y="{y + 13}" font-size="12" font-style="italic" {paint("muted")}>{escape(note)}</text>')
                continue
            w = value * scale
            label = fmt(value, p["unit"])
            out.append(f'<path d="{bar(base, y, w, BAR_H)}" {paint(role)}><title>{escape(apps[key])}: {label}</title></path>')
            out.append(f'<text x="{base + w + 6:.1f}" y="{y + 13}" font-size="12" font-variant-numeric="tabular-nums" {paint("text")}>{label}</text>')

    y = TOP + rows * PANEL_H + 8
    for line in notes:
        y += 16
        out.append(f'<text x="0" y="{y - 4}" font-size="11" {paint("muted")}>{escape(line)}</text>')
    out.append("</svg>")
    return "\n".join(out) + "\n"


def main():
    if len(sys.argv) != 2:
        print("usage: comparison.py <out-dir>", file=sys.stderr)
        return 2
    data = json.loads((Path(__file__).with_name("comparison.json")).read_text(encoding="utf-8"))
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for name, theme in (("light", THEMES["light"]), ("dark", THEMES["dark"]), ("inline", None)):
        (out / f"comparison-{name}.svg").write_text(render(data, theme), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
