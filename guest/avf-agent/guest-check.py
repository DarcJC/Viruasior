#!/usr/bin/env python3
"""Fixed, non-root guest diagnostics. Does not open the user's browser profile."""
import hashlib
import json
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request


def run(args, timeout=15):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=True)
    return result.stdout.strip()


def fonts():
    run(["fc-cache", "-f", "/usr/local/share/fonts/viruaisor"], 25)
    names = run(["fc-list", ":lang=zh", "family"])
    if "Noto Sans CJK SC" not in names or "Noto Serif CJK SC" not in names:
        raise RuntimeError("Chinese font coverage missing")
    return {"sans": run(["fc-match", "-f", "%{family}", "sans-serif:lang=zh-cn"]),
            "serif": run(["fc-match", "-f", "%{family}", "serif:lang=zh-cn"]), "coverage": True}


def network(benchmark=False):
    # Explicitly disable proxy environment variables: this tests kernel forwarding.
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    started = time.monotonic()
    if benchmark:
        with client.open("https://proof.ovh.net/files/1Mb.dat", timeout=25) as response:
            count = 0
            while chunk := response.read(65536):
                count += len(chunk)
                if count > 1048576:
                    raise RuntimeError("Unexpected benchmark response size")
        seconds = time.monotonic() - started
        if count != 1048576:
            raise RuntimeError("Incomplete benchmark response")
        return {"endpoint": "ovh-1MiB", "bytes": count, "seconds": round(seconds, 3), "mbps": round(count * 8 / seconds / 1000000, 2)}
    addresses = socket.getaddrinfo("www.cloudflare.com", 443, type=socket.SOCK_STREAM)
    with client.open("https://www.cloudflare.com/cdn-cgi/trace", timeout=15) as response:
        values = dict(line.split("=", 1) for line in response.read(8192).decode().splitlines() if "=" in line)
    return {"dns": bool(addresses), "https": True, "country": values.get("loc"),
            "exitHash": hashlib.sha256(values["ip"].encode()).hexdigest()[:16], "seconds": round(time.monotonic()-started, 3)}


def render():
    information = fonts()
    browser = shutil.which("chromium") or shutil.which("chromium-browser")
    if not browser:
        raise RuntimeError("Chromium is not installed")
    directory = pathlib.Path("/mnt/shared/viruaisor-validation")
    directory.mkdir(exist_ok=True)
    if directory.is_symlink() or (directory / "cjk.png").is_symlink():
        raise RuntimeError("Unsafe render destination")
    sample = """<!doctype html><meta charset=utf-8><style>body{background:white;color:#172033;padding:32px;font-size:42px}p{margin:18px 0}b{font-family:'Noto Sans CJK SC'}em{font-family:'Noto Serif CJK SC';font-style:normal}</style><p>中文显示测试：你好，世界！</p><p><b>简体中文 · 繁體中文 · Chromium</b></p><p><em>汉字与標點：网络、浏览器、字体。</em></p>"""
    with tempfile.TemporaryDirectory(prefix="viruaisor-cjk-") as temporary:
        page = pathlib.Path(temporary) / "sample.html"
        page.write_text(sample, encoding="utf-8")
        args = [browser, "--headless", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage", "--no-first-run",
                "--disable-background-networking", "--disable-extensions", "--hide-scrollbars", "--window-size=1280,400",
                f"--user-data-dir={temporary}/profile", f"--screenshot={directory}/cjk.png", page.as_uri()]
        run(args, 35)
    pixels = (directory / "cjk.png").read_bytes()
    if not pixels.startswith(b"\x89PNG\r\n\x1a\n") or len(pixels) < 4096:
        raise RuntimeError("Chromium did not render the font sample")
    information.update(pngBytes=len(pixels), pngSha256=hashlib.sha256(pixels).hexdigest())
    return information


if __name__ == "__main__":
    operations = {"FONT_STATUS": fonts, "NETWORK_STATUS": network, "NETWORK_BENCH": lambda: network(True), "CHROMIUM_RENDER": render}
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in operations:
            raise ValueError("Unsupported fixed diagnostic")
        result = {"ok": True, **operations[sys.argv[1]]()}
    except Exception as error:
        result = {"ok": False, "error": type(error).__name__, "detail": str(error)[:200]}
    print("OK " + json.dumps(result, ensure_ascii=True, separators=(",", ":")))
