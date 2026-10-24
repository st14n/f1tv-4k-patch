#!/usr/bin/env python3
"""Check APKMirror RSS feeds for new F1TV Android TV versions."""

import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
from urllib.request import Request, urlopen

# Several feeds, merged: each one can break on its own when F1 renames the app, APKMirror
# changes the app slug, or a new minSdk moves builds to another variant. The developer feed
# lists every F1 app (TV builds are told apart by the "-tv" version suffix), so it keeps
# working through renames; the variant feed links straight to a variant page.
# APKMIRROR_FEED_URLS (space-separated) replaces the list without a code change.
DEFAULT_FEED_URLS = [
    "https://www.apkmirror.com/apk/formula-one-digital-media-limited/feed/",
    "https://www.apkmirror.com/apk/formula-one-digital-media-limited/"
    "f1-tv-android-tv/variant-%7B%22minapi_slug%22%3A%22minapi-25%22%7D/feed/",
]

# e.g. "... (Android TV) 3.0.50-SP168.9.0-release-R55-tv (arm64-v8a + arm-v7a) ..."
TV_VERSION_RE = re.compile(r"(?<![\w.])(\d+(?:\.\d+)+-\S+?-tv)\b", re.IGNORECASE)


def feed_urls() -> list[str]:
    override = os.environ.get("APKMIRROR_FEED_URLS", "").split()
    return override or DEFAULT_FEED_URLS


def fetch_feed(url: str, retries: int = 3) -> str:
    for attempt in range(retries):
        try:
            req = Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urlopen(req, timeout=30) as resp:
                return resp.read().decode()
        except Exception as e:
            if attempt < retries - 1:
                wait = 10 * (attempt + 1)
                print(f"Attempt {attempt + 1}/{retries} failed for {url}: {e}, retrying in {wait}s...", file=sys.stderr)
                time.sleep(wait)
            else:
                raise


def _version_key(short: str) -> list[int]:
    return [int(x) for x in short.split(".")]


def _split_link(link: str) -> tuple[str, str]:
    """(release page URL, variant page URL or "") for an APKMirror item link.

    Release page: .../<app>/<app>-<version>-release/
    Variant page: .../<app>/<app>-<version>-release/<variant>-android-apk-download/
    """
    parts = link.rstrip("/").split("/")
    for i in range(len(parts) - 1, 0, -1):
        if parts[i].endswith("-release"):
            release_url = "/".join(parts[: i + 1]) + "/"
            return release_url, (link if i < len(parts) - 1 else "")
    return link, ""


def parse_items(xml_text: str) -> list[dict]:
    """Every TV build in a feed.

    Matches the version token itself, not the app name: F1 renames the listing now and then
    (e.g. "F1 TV (Android TV)" -> "F1 TV: Stream F1® Live (Android TV)").
    """
    entries = []
    root = ET.fromstring(xml_text)
    for item in root.iterfind(".//channel/item"):
        title = item.findtext("title", "").strip()
        link = item.findtext("link", "").strip()
        m = TV_VERSION_RE.search(title)
        if not m or not link:
            continue
        version = m.group(1)
        release_url, variant_url = _split_link(link)
        entries.append({
            "title": title,
            "link": link,
            "release_url": release_url,
            "variant_url": variant_url,
            "pub_date": item.findtext("pubDate", "").strip(),
            "version": version,
            # Short version for the release tag (e.g. "3.0.50")
            "version_short": re.match(r"\d+(?:\.\d+)+", version).group(0),
        })
    return entries


def parse_latest(xml_texts: list[str]) -> dict | None:
    """Highest TV version across all feeds (feed ordering does not matter)."""
    best = None
    variant_for = {}
    for xml_text in xml_texts:
        try:
            entries = parse_items(xml_text)
        except ET.ParseError as e:
            print(f"Skipping unparsable feed: {e}", file=sys.stderr)
            continue
        for e in entries:
            if e["variant_url"]:
                variant_for.setdefault(e["version_short"], e["variant_url"])
            if best is None or _version_key(e["version_short"]) > _version_key(best["version_short"]):
                best = e
    if best and not best["variant_url"]:
        # Release page from one feed, variant page for the same version from another
        best["variant_url"] = variant_for.get(best["version_short"], "")
    return best


def write_outputs(latest: dict | None) -> None:
    github_output = os.environ.get("GITHUB_OUTPUT")
    if not github_output:
        return
    keys = ("version", "version_short", "release_url", "variant_url", "title")
    with open(github_output, "a") as f:
        for k in keys:
            f.write(f"{k}={latest[k] if latest else ''}\n")


def main():
    xml_texts = []
    for url in feed_urls():
        try:
            xml_texts.append(fetch_feed(url))
        except Exception as e:
            print(f"WARNING: Failed to fetch {url}: {e}", file=sys.stderr)

    latest = parse_latest(xml_texts)
    if not latest:
        print("ERROR: No F1 TV (Android TV) version found in any APKMirror feed", file=sys.stderr)
        # On scheduled runs, exit gracefully so the workflow doesn't spam failures
        if os.environ.get("GITHUB_EVENT_NAME") == "schedule":
            print("Scheduled run — skipping (will retry next run)", file=sys.stderr)
            write_outputs(None)
            sys.exit(0)
        sys.exit(1)

    print(f"Latest: {latest['version']}", file=sys.stderr)
    print(f"Release URL: {latest['release_url']}", file=sys.stderr)
    print(f"Variant URL: {latest['variant_url'] or '(none, resolved on the release page)'}", file=sys.stderr)

    if os.environ.get("GITHUB_OUTPUT"):
        write_outputs(latest)
    else:
        # Print JSON for local use
        print(json.dumps(latest, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
