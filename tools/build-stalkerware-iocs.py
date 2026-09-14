#!/usr/bin/env python3
"""Turn Echap's stalkerware indicators into Bulwark's bundled asset.

Run from the repo root, with the two upstream files already downloaded:

    python tools/build-stalkerware-iocs.py ioc.yaml watchware.yaml

Writes `app/src/main/assets/stalkerware-iocs.json`.

## Why a script and not a one-off

The UAD asset was converted by hand and can only be trusted because somebody
says so. An asset nobody can regenerate is an asset nobody can audit, and
v1.0 wants a reproducible build verified by F-Droid - which means every
byte in the APK has to be explainable. This is that explanation, executable.

## What it keeps, and what it drops

Keeps **name, type, packages, certificates**. Those are the two signals
Bulwark matches on, plus what it needs to say afterwards.

Drops:

- `c2`, `websites`, `distribution` - the domain blocklist was withdrawn on
  2026-09-12 (it would route every app through the tunnel), so these are bytes
  Bulwark would ship and never read.
- `ios_bundles` - not this platform.
- `certificate_organizations` and `certificate_cname_re` - fuzzy matches on
  the certificate's organisation or common name. Tempting, because they catch
  renamed variants, and deliberately left out for now: they match on a string
  somebody chose rather than a key they hold, and a false positive here tells
  a person an ordinary app is spying on them. Worth revisiting with its own
  false-positive testing, not as a free extra.

`type` is taken from **which file the entry came from**, never from the
`type:` field. Stalkerware and watchware are different claims about a person's
life and the upstream separates them by file; reading a field would be one
refactor away from calling a parental-control tool stalkerware.
"""

import json
import pathlib
import sys

try:
    import yaml
except ImportError:
    sys.exit("needs pyyaml: pip install pyyaml")

OUT = pathlib.Path("app/src/main/assets/stalkerware-iocs.json")


def load(path: pathlib.Path, kind: str) -> list[dict]:
    entries = yaml.safe_load(path.read_text(encoding="utf-8"))
    out = []
    for entry in entries:
        packages = sorted(set(entry.get("packages") or []))
        # Lower-cased because Bulwark hashes to lower-case hex and a
        # case-sensitive comparison against upstream's upper-case would match
        # nothing while looking entirely correct.
        certificates = sorted({c.lower() for c in (entry.get("certificates") or []) if c})
        if not packages and not certificates:
            # Nothing Bulwark can match on. Carried nowhere, counted below.
            continue
        out.append({"n": entry["name"], "t": kind, "p": packages, "c": certificates})
    return out


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    stalkerware = pathlib.Path(sys.argv[1])
    watchware = pathlib.Path(sys.argv[2])

    total = 0
    for path in (stalkerware, watchware):
        total += len(yaml.safe_load(path.read_text(encoding="utf-8")))

    entries = load(stalkerware, "stalkerware") + load(watchware, "watchware")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(entries, separators=(",", ":"), ensure_ascii=False),
        encoding="utf-8",
    )

    packages = {p for e in entries for p in e["p"]}
    certificates = {c for e in entries for c in e["c"]}
    skipped = total - len(entries)
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
    print(f"  {len(entries)} of {total} upstream entries are matchable")
    print(f"  {skipped} carry neither a package name nor a certificate and were dropped")
    print(f"  {len(packages)} distinct packages, {len(certificates)} distinct certificates")


if __name__ == "__main__":
    main()
