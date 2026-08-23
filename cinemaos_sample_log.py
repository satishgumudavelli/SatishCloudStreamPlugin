#!/usr/bin/env python3
"""
Standalone reproduction of CinemaOsProvider/CinemaOsExtractor.kt's invokeCinemaos, for
generating a sample output log without building/running the Android plugin.

Usage: python3 cinemaos_sample_log.py [tmdbId] [imdbId] [title] [year] [--tv season episode]
Writes cinemaos_sample_output.log in repo root.
"""
import hashlib
import hmac
import json
import sys
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

BASE = "https://cinemaos.tech"
PRIMARY_KEY = "a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5"
SECONDARY_KEY = "d3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3"
ENC_KEY_HEX = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
GATE_TOKEN = "6775dc8e702c08643385273df088c14952c590ddda02d14f"

SCRAPERS = [
    "va", "vf", "rive", "v2", "vdn", "zm", "lm", "vs", "vl", "vz", "cs", "mt", "hexa",
    "ms", "vp", "ts", "vy", "fx", "py", "fz", "fs", "cz", "fn", "sc", "vdy", "nhd", "vx",
    "h0", "mb2", "q4", "s3", "vc", "vn", "z2",
]


def hmac_sha256_hex(key: str, message: str) -> str:
    return hmac.new(key.encode(), message.encode(), hashlib.sha256).hexdigest()


def cinemaos_secret(tmdb_id: int, imdb_id: str, season: int | None, episode: int | None) -> str:
    parts = [f"tmdbId:{tmdb_id}", f"imdbId:{imdb_id}"]
    if season is not None:
        parts.append(f"seasonId:{season}")
    if episode is not None:
        parts.append(f"episodeId:{episode}")
    content = "|".join(parts)
    return hmac_sha256_hex(SECONDARY_KEY, hmac_sha256_hex(PRIMARY_KEY, content))


def decrypt(data: dict) -> dict:
    if data.get("salt") and int(data.get("version", 0)) >= 1:
        key = hashlib.pbkdf2_hmac("sha256", ENC_KEY_HEX.encode("ascii"), bytes.fromhex(data["salt"]), 100_000, 32)
    else:
        key = bytes.fromhex(ENC_KEY_HEX)
    iv = bytes.fromhex(data["cin"])
    ct_and_tag = bytes.fromhex(data["encrypted"]) + bytes.fromhex(data["mao"])
    pt = AESGCM(key).decrypt(iv, ct_and_tag, None)
    return json.loads(pt)


def fetch_scraper(base: str, scraper: str, tmdb_id: int, imdb_id: str, title: str, year, season, episode, headers) -> dict:
    media_type = "movie" if season is None else "tv"
    secret = cinemaos_secret(tmdb_id, imdb_id, season, episode)
    params = {
        "type": media_type, "tmdbId": str(tmdb_id), "imdbId": imdb_id, "t": title or "",
        "ry": str(year or ""), "secret": secret, "_ck": GATE_TOKEN, "scraper": scraper,
    }
    if season is not None:
        params["seasonId"] = str(season)
    if episode is not None:
        params["episodeId"] = str(episode)
    url = f"{base}/api/providerv5/scrape?{urllib.parse.urlencode(params)}"
    full_headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        **headers,
    }
    req = urllib.request.Request(url, headers=full_headers)
    with urllib.request.urlopen(req, timeout=20) as resp:
        body = json.loads(resp.read().decode())
    if body.get("encrypted") and "data" in body:
        return decrypt(body["data"])
    return body


def main():
    tmdb_id = int(sys.argv[1]) if len(sys.argv) > 1 else 1323244
    imdb_id = sys.argv[2] if len(sys.argv) > 2 else "tt6791350"
    title = sys.argv[3] if len(sys.argv) > 3 else ""
    year = sys.argv[4] if len(sys.argv) > 4 else None
    season = episode = None
    if "--tv" in sys.argv:
        i = sys.argv.index("--tv")
        season, episode = int(sys.argv[i + 1]), int(sys.argv[i + 2])

    media_type = "movie" if season is None else "tv"
    headers = {"Referer": f"{BASE}/watch/{media_type}/{tmdb_id}"}

    lines = [f"CinemaOS sample output - tmdbId={tmdb_id} imdbId={imdb_id} season={season} episode={episode}\n"]
    seen_urls = set()
    for scraper in SCRAPERS:
        try:
            resolved = fetch_scraper(BASE, scraper, tmdb_id, imdb_id, title, year, season, episode, headers)
        except Exception as e:
            lines.append(f"[{scraper}] error: {e}")
            continue

        sources = resolved.get("sources") or {}
        if not sources:
            lines.append(f"[{scraper}] sources: {{}}")
            continue

        for server_name, entry in sources.items():
            label = entry.get("server") or server_name
            flat_url = entry.get("url")
            if flat_url:
                variants = [("", flat_url, entry.get("type", ""))]
            else:
                variants = [
                    (f"-{q}", q0.get("url"), q0.get("type", ""))
                    for q, q0 in (entry.get("qualities") or {}).items()
                    if q0.get("url")
                ]
            for tag, src_url, src_type in variants:
                if not src_url or src_url in seen_urls:
                    continue
                seen_urls.add(src_url)
                is_hls = src_type == "hls" or ".m3u8" in src_url.lower()
                lines.append(
                    f"[{scraper}] CinemaOS-{server_name}{tag}  name=\"CinemaOS [{label}]{(' ' + tag.lstrip('-')) if tag else ''}\"  "
                    f"type={'M3U8' if is_hls else 'VIDEO'}  url={src_url}"
                )

        for cap in resolved.get("captions") or []:
            sub_url = cap.get("url")
            if sub_url:
                lang = cap.get("language") or cap.get("label") or "Unknown"
                lines.append(f"[{scraper}] subtitle lang={lang} url={sub_url}")

    out_path = "cinemaos_sample_output.log"
    with open(out_path, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"\nWrote {out_path}")


if __name__ == "__main__":
    main()
