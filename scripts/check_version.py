#!/usr/bin/env python3
"""Check APKMirror RSS feed for new F1TV Android TV versions."""

import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
from urllib.request import Request, urlopen

FEED_URL = (
    "https://www.apkmirror.com/apk/formula-one-digital-media-limited/"
    "f1-tv-android-tv/variant-%7B%22minapi_slug%22%3A%22minapi-25%22%7D/feed/"
)


def fetch_feed(retries: int = 5):
    for attempt in range(retries):
        try:
            req = Request(FEED_URL, headers={"User-Agent": "Mozilla/5.0"})
            with urlopen(req, timeout=30) as resp:
                return resp.read().decode()
        except Exception as e:
            if attempt < retries - 1:
                wait = 10 * (attempt + 1)
                print(f"Attempt {attempt + 1}/{retries} failed: {e}, retrying in {wait}s...", file=sys.stderr)
                time.sleep(wait)
            else:
                raise


def _version_key(short: str) -> list[int]:
    return [int(x) for x in short.split(".")]


def parse_latest(xml_text: str) -> dict | None:
    """Newest TV build in the feed.

    Matches the version token itself, not the app name: F1 renames the listing now and then
    (e.g. "F1 TV (Android TV)" -> "F1 TV: Stream F1® Live (Android TV)"). Picks the highest
    version rather than the first item, so feed ordering does not matter either.
    """
    root = ET.fromstring(xml_text)
    best = None
    for item in root.iterfind(".//channel/item"):
        title = item.findtext("title", "").strip()
        link = item.findtext("link", "").strip()
        pub_date = item.findtext("pubDate", "").strip()

        # e.g. "... (Android TV) 3.0.50-SP168.9.0-release-R55-tv (arm64-v8a + arm-v7a) ..."
        m = re.search(r"(?<![\w.])(\d+(?:\.\d+)+-\S+?-tv)\b", title, re.IGNORECASE)
        if not m:
            continue
        version = m.group(1)
        # Short version for the release tag (e.g. "3.0.50")
        version_short = re.match(r"\d+(?:\.\d+)+", version).group(0)

        # Derive release page URL (strip variant-specific suffix)
        # RSS link: .../f1-tv-...-release/f1-tv-...-android-apk-download/
        # Release page: .../f1-tv-...-release/
        release_url = link
        if link.count("/") > 6:
            release_url = link.rstrip("/").rsplit("/", 1)[0] + "/"

        entry = {
            "title": title,
            "link": link,
            "release_url": release_url,
            "pub_date": pub_date,
            "version": version,
            "version_short": version_short,
        }
        if best is None or _version_key(version_short) > _version_key(best["version_short"]):
            best = entry
    return best


def main():
    try:
        xml_text = fetch_feed()
    except Exception as e:
        print(f"ERROR: Failed to fetch RSS feed after retries: {e}", file=sys.stderr)
        # On scheduled runs, exit gracefully so the workflow doesn't spam failures
        if os.environ.get("GITHUB_EVENT_NAME") == "schedule":
            print("Scheduled run — skipping (will retry next hour)", file=sys.stderr)
            github_output = os.environ.get("GITHUB_OUTPUT")
            if github_output:
                with open(github_output, "a") as f:
                    f.write("version=\n")
                    f.write("version_short=\n")
                    f.write("release_url=\n")
                    f.write("variant_url=\n")
                    f.write("title=\n")
            sys.exit(0)
        sys.exit(1)

    latest = parse_latest(xml_text)
    if not latest or not latest["version"]:
        print("ERROR: Could not parse latest version from feed", file=sys.stderr)
        sys.exit(1)

    print(f"Latest: {latest['version']}", file=sys.stderr)
    print(f"Release URL: {latest['release_url']}", file=sys.stderr)

    # Write to GITHUB_OUTPUT if running in CI
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as f:
            f.write(f"version={latest['version']}\n")
            f.write(f"version_short={latest['version_short']}\n")
            f.write(f"release_url={latest['release_url']}\n")
            f.write(f"variant_url={latest['link']}\n")
            f.write(f"title={latest['title']}\n")
    else:
        # Print JSON for local use
        print(json.dumps(latest, indent=2))


if __name__ == "__main__":
    main()
