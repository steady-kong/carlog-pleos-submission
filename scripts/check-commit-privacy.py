#!/usr/bin/env python3
"""Reject staged home paths and non-private Git author/committer identities."""
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args])


for kind in ("AUTHOR", "COMMITTER"):
    identity = git("var", f"GIT_{kind}_IDENT").decode("utf-8", "replace")
    match = re.search(r"<([^<>]+)>", identity)
    if not match or not re.fullmatch(r"[A-Za-z0-9.+_-]+@users\.noreply\.github\.com", match.group(1), re.I):
        print(f"커밋 차단: {kind} 이메일은 GitHub noreply 주소여야 합니다.", file=sys.stderr)
        sys.exit(1)

paths = git("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z").split(b"\0")
patterns = (re.compile(rb"/" + rb"home/[^/\s\x00\"']+/"),
            re.compile(rb"/" + rb"Users/[^/\s\x00\"']+/"),
            re.compile(rb"[A-Za-z]:\\Users\\[^\\\s\x00\"']+\\", re.I),
            re.compile(rb"\b[a-z0-9-]+\.tail[a-z0-9-]+\.ts\.net\b", re.I))
blocked = []
for raw in filter(None, paths):
    path = raw.decode("utf-8", "surrogateescape")
    content = git("show", f":{path}")
    if any(pattern.search(content) for pattern in patterns):
        blocked.append(path)
if blocked:
    print("커밋 차단: 개인 홈 경로 또는 내부 호스트명이 포함된 파일:", file=sys.stderr)
    for path in blocked:
        print(f"  {path}", file=sys.stderr)
    sys.exit(1)
