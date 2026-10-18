# SPDX-License-Identifier: MIT
# Copyright (c) 2026 DarkRelay
"""Refuse to publish if MicroDeck carries something private: a personal user path, a home-network
or tailnet address, a home-lab hostname, an e-mail address, a token or key, a filled-in secret, an
adb device address, or an em dash. Ported from PhoneDeck's checker, adapted for an Android project.

    python tools/privacy_check.py                 the working tree (tracked + new, not ignored)
    python tools/privacy_check.py --public        also the public set: tracked files minus anything
                                                  marked export-ignore in .gitattributes
    python tools/privacy_check.py --dist          also the built APK(s) in app/build/outputs/apk:
                                                  every entry inside, raw and UTF-16 folded, plus
                                                  aapt2's decoded manifest, resources and strings
    python tools/privacy_check.py --root <dir>    check another copy of the tree (tests)

Exit 1 and a list of file:line (or file: for the build) if anything is found.

The patterns are generic, so nothing private is written here. Exact values that no generic pattern
catches (a real name, a device serial) go one per line in tools/privacy-local.txt, which is
gitignored and never leaves this PC. A plain line matches anywhere, ignoring case; a line starting
with "case:" matches only as a whole word with the exact case. Binary files in the tree are
skipped; license texts quote their authors' addresses, so those are skipped too."""
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
LOCAL = HERE / "privacy-local.txt"

# public addresses that may appear (a published contact address would go here)
EMAIL_ALLOW = ["users.noreply.github.com", "github.com", "example.com", "example.org"]

USER_PATH = ("a personal user path",
             re.compile(r"[A-Za-z]:[\\/]+Users[\\/]+(?!Public\b|Default\b|<|%|\$|\{)[A-Za-z0-9_.-]+"
                        r"|/(?:home|Users)/(?!runner\b|builder\b|<|\$|\{)[a-z][a-z0-9_.-]+", re.I))
PRIVATE_IP = ("a private network address",
              re.compile(r"(?<!Version: )(?<![\d.])(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))"
                         r"\.\d{1,3}\.\d{1,3}(?![\d.])"))
TAILNET = ("a tailnet name or address",
           re.compile(r"\btailscale\b|\.ts\.net\b|(?<![\d.])100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])"
                      r"\.\d{1,3}\.\d{1,3}(?![\d.])", re.I))
HOMELAB = ("a home-lab hostname",
           re.compile(r"\bnexus(?:-config|-wire|\.)|\bhal9000\b|\bhal69000\b|[a-z0-9-]+\.darkrelay\.net", re.I))
TOKEN = ("a token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"
                               r"|\beyJ[A-Za-z0-9_-]{20,}\.eyJ|\bAKIA[0-9A-Z]{16}\b|\bAIza[0-9A-Za-z_-]{35}\b"))
KEY = ("a private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"))
EMAIL = ("an e-mail address",
         re.compile(r"\b[A-Za-z0-9._%+-]+@(?!(?:" + "|".join(re.escape(d) for d in EMAIL_ALLOW) + r")\b)"
                    r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b"))
# key = value with a real-looking value (not empty, not a placeholder, not a variable reference,
# not code that reads the value from somewhere else, like props.getProperty("storePassword"))
SECRET = ("a filled-in secret",
          re.compile(r"(?i)\b(?:password|passwd|secret|api[_-]?key|token|storePassword|keyPassword)\b"
                     r"\s*[=:]\s*(?![\"']?\s*(?:$|<|\$|\{|%|changeme\b|xxx|\*\*\*|null\b|none\b|\"\"|''))"
                     r"(?![A-Za-z_][\w.]*\()"
                     r"[\"']?[^\s\"'<>]{6,}"))
ADB = ("an adb device address or serial",
       re.compile(r"\badb(?:\.exe)?\s+(?:-s|connect)\s+(?!<|\$|\{)\S+|(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}:\d{4,5}\b"))
EMDASH = ("an em dash", re.compile(chr(0x2014)))

TREE_PATTERNS = [USER_PATH, PRIVATE_IP, TAILNET, HOMELAB, TOKEN, KEY, EMAIL, SECRET, ADB, EMDASH]
# the APK also carries the Kotlin standard library; its strings are not ours, but none of these
# should ever appear in it either way
DIST_PATTERNS = [USER_PATH, PRIVATE_IP, TAILNET, HOMELAB, TOKEN, KEY, EMAIL, ADB, EMDASH]
SKIP_SUFFIX = {".png", ".jpg", ".jpeg", ".webp", ".ico", ".ttf", ".pdf", ".jar"}
SKIP_PREFIX = ("licenses/", "LICENSE", "app/src/main/assets/licenses/")   # verbatim license texts


def local_values():
    """Exact private strings from the gitignored tools/privacy-local.txt (# comments allowed)."""
    if not LOCAL.is_file():
        return []
    out = []
    for ln in LOCAL.read_text(encoding="utf-8").splitlines():
        v = ln.strip()
        if not v or v.startswith("#"):
            continue
        if v.startswith("case:"):
            out.append(("a private value from privacy-local.txt",
                        re.compile(r"(?<![A-Za-z0-9_])" + re.escape(v[5:].strip()) + r"(?![A-Za-z0-9_])")))
        else:
            out.append(("a private value from privacy-local.txt", re.compile(re.escape(v), re.I)))
    return out


def git_lines(root, *args):
    out = subprocess.run(["git", *args], cwd=root, capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8").split("\0") if p]


def tracked(root):
    try:
        return git_lines(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard", ".")
    except (subprocess.CalledProcessError, OSError):
        # not a git checkout (a test copy): every file except build output
        skip = {".git", ".gradle", ".kotlin", "build", "__pycache__", ".idea"}
        files = []
        for d, dirs, names in os.walk(root):
            dirs[:] = [x for x in dirs if x not in skip]
            files += [os.path.relpath(os.path.join(d, n), root).replace("\\", "/") for n in names]
        return files


def public_set(root):
    """Tracked files minus the ones marked export-ignore (what a git archive of the tree ships)."""
    files = git_lines(root, "ls-files", "-z", "--cached", ".")
    out = subprocess.run(["git", "check-attr", "-z", "export-ignore", "--stdin"], cwd=root,
                         input="\0".join(files).encode("utf-8") + b"\0", capture_output=True,
                         check=True).stdout.decode("utf-8").split("\0")
    ignored = {out[i] for i in range(0, len(out) - 2, 3) if out[i + 2] == "set"}
    return [f for f in files if f not in ignored and (root / f).is_file()]


def scan_text(label, text, patterns, found, per_line=True):
    if per_line:
        for n, line in enumerate(text.splitlines(), 1):
            for what, rx in patterns:
                if rx.search(line):
                    found.append(f"{label}:{n}: {what}")
    else:
        for what, rx in patterns:
            if rx.search(text):
                found.append(f"{label}: {what}")


def check_files(root, files, extra):
    found = []
    for rel in files:
        if Path(rel).suffix.lower() in SKIP_SUFFIX or rel.startswith(SKIP_PREFIX):
            continue
        if rel == "tools/privacy-local.txt":
            continue
        try:
            text = (root / rel).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        scan_text(rel, text, TREE_PATTERNS + extra, found)
    return found


def aapt2(root):
    """aapt2 from the SDK in local.properties or ANDROID_HOME, newest build-tools first."""
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    lp = root / "local.properties"
    if lp.is_file():
        m = re.search(r"^sdk\.dir=(.+)$", lp.read_text(encoding="utf-8"), re.M)
        if m:
            sdk = m.group(1).strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        return None
    bt = Path(sdk) / "build-tools"
    for d in sorted(bt.glob("*"), reverse=True):
        for name in ("aapt2.exe", "aapt2"):
            if (d / name).is_file():
                return d / name
    return None


def check_apk(apk, extra, root):
    found, pats, n = [], DIST_PATTERNS + extra, 0
    rel = apk.relative_to(root).as_posix()
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            n += 1
            data = z.read(info)
            label = f"{rel}!{info.filename}"
            scan_text(label, data.decode("latin-1"), pats, found, per_line=False)
            # binary XML and resources.arsc keep strings as UTF-16LE
            scan_text(label + " (utf-16)", data.replace(b"\x00", b"").decode("latin-1"), pats, found,
                      per_line=False)
    tool = aapt2(root)
    if tool:
        for what in (["dump", "xmltree", "--file", "AndroidManifest.xml"], ["dump", "resources"],
                     ["dump", "strings"], ["dump", "badging"]):
            r = subprocess.run([str(tool), *what, str(apk)], capture_output=True)
            n += 1
            scan_text(f"{rel} (aapt2 {what[1]})", r.stdout.decode("utf-8", "replace"), pats, found,
                      per_line=False)
    else:
        print("privacy check: aapt2 not found, decoded resources not scanned")
    seen, out = set(), []
    for f in found:
        k = f.replace(" (utf-16)", "")
        if k not in seen:
            seen.add(k)
            out.append(f)
    return out, n


def main(argv):
    root = ROOT
    if "--root" in argv:
        root = Path(argv[argv.index("--root") + 1]).resolve()
    extra = local_values()
    files = tracked(root)
    found = check_files(root, files, extra)
    for f in found:
        print(f)
    print(f"privacy check: {len(found)} finding(s) in {len(files)} files (tracked + new)"
          + (f" (+{len(extra)} local values)" if extra else ""))
    bad = bool(found)
    if "--public" in argv:
        pub = public_set(root)
        pfound = check_files(root, pub, extra)
        for f in pfound:
            print("public: " + f)
        print(f"privacy check: {len(pfound)} finding(s) in the public set ({len(pub)} files)")
        bad = bad or bool(pfound)
    if "--dist" in argv:
        apks = sorted((root / "app" / "build" / "outputs" / "apk").rglob("*.apk"))
        if not apks:
            print("privacy check: no APK under app/build/outputs/apk")
            return 1
        for apk in apks:
            dfound, dn = check_apk(apk, extra, root)
            for f in dfound:
                print(f)
            print(f"privacy check: {len(dfound)} finding(s) in {apk.name} ({dn} entries and dumps)")
            bad = bad or bool(dfound)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
