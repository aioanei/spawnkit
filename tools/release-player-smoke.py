"""Open a local standalone game or Arcade play URL in Chrome and verify an authored shape is rendered.

Requires Python 3.11+, websocket-client, Pillow and Chrome (CHROME_BIN overrides its location).
Pass --move-right for the Score + collect fixture to verify the keyboard rule moves the player.
"""
import argparse
import base64
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from urllib.parse import urljoin, urlsplit
from urllib.request import urlopen

from PIL import Image, ImageChops
from websocket import create_connection


def read_json(url):
    with urlopen(url, timeout=10) as response:
        return json.load(response)


class Tab:
    def __init__(self, socket):
        self.socket = socket
        self.sequence = 0

    def send(self, method, **params):
        self.sequence += 1
        self.socket.send(json.dumps(dict(id=self.sequence, method=method, params=params)))
        while True:
            event = json.loads(self.socket.recv())
            if event.get("method") == "Runtime.exceptionThrown":
                raise RuntimeError(event["params"]["exceptionDetails"].get("text"))
            if event.get("id") == self.sequence:
                if "error" in event:
                    raise RuntimeError(event["error"])
                return event["result"]

    def screenshot(self):
        png = self.send("Page.captureScreenshot", format="png")["data"]
        return Image.open(io.BytesIO(base64.b64decode(png))).convert("RGB")


def check_play(url, destination, move):
    parts = urlsplit(url)
    if parts.scheme != "http" or parts.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("Use a local HTTP server for this test.")
    # A directory slash matters for the player's relative package and module imports.
    with urlopen(url, timeout=10) as page:
        url = page.geturl()
    package = read_json(urljoin(url, "game.spawnkit.json"))
    scene = next(s for s in package["project"]["scenes"] if s["id"] == package["startScene"])
    colors = set()
    for obj in scene["objects"]:
        for component in obj["components"]:
            color = component["values"].get("color")
            if component["id"] == "graphics:shape" and color:
                value = color["value"]
                colors.add(((value >> 16) & 255, (value >> 8) & 255, value & 255))
    if not colors:
        raise ValueError("The fixture must have a visible colored shape in its start scene.")
    chrome = os.environ.get("CHROME_BIN") or shutil.which("google-chrome") or "C:/Program Files/Google/Chrome/Application/chrome.exe"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="spawnkit-chrome-", ignore_cleanup_errors=True) as profile:
        with subprocess.Popen(
            [chrome, "--headless", "--no-first-run", "--no-default-browser-check", "--window-size=960,640",
             "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0", f"--user-data-dir={profile}", "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        ) as browser:
            tab = None
            try:
                port_file = Path(profile, "DevToolsActivePort")
                for _ in range(200):
                    if port_file.is_file() and port_file.read_text().splitlines():
                        break
                    if browser.poll() is not None:
                        raise RuntimeError("Chrome exited before opening the test profile.")
                    time.sleep(.1)
                port = port_file.read_text().splitlines()[0]
                targets = read_json(f"http://127.0.0.1:{port}/json/list")
                target = next(t for t in targets if t["type"] == "page" and t["url"] == "about:blank")
                tab = Tab(create_connection(target["webSocketDebuggerUrl"], timeout=15, suppress_origin=True))
                tab.send("Page.enable")
                tab.send("Runtime.enable")
                tab.send("Page.navigate", url=url)
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    title = tab.send("Runtime.evaluate", expression="document.title", returnByValue=True)["result"].get("value")
                    if title == package["title"]:
                        image = tab.screenshot()
                        pixels = dict((rgb, count) for count, rgb in (image.getcolors(image.width * image.height) or []))
                        if any(pixels.get(rgb, 0) >= 25 for rgb in colors):
                            break
                    time.sleep(.25)
                else:
                    raise RuntimeError("Chrome did not render the package title and an authored shape.")
                if move:
                    tab.send("Input.dispatchMouseEvent", type="mousePressed", x=10, y=10, button="left", clickCount=1)
                    tab.send("Input.dispatchMouseEvent", type="mouseReleased", x=10, y=10, button="left", clickCount=1)
                    before = tab.screenshot()
                    tab.send("Input.dispatchKeyEvent", type="keyDown", code="ArrowRight", key="ArrowRight", windowsVirtualKeyCode=39)
                    time.sleep(1.3)
                    tab.send("Input.dispatchKeyEvent", type="keyUp", code="ArrowRight", key="ArrowRight", windowsVirtualKeyCode=39)
                    image = tab.screenshot()
                    if not ImageChops.difference(image, before).getbbox():
                        raise RuntimeError("Pressing Right did not change the rendered game.")
                image.save(destination)
                print(f"PASS: Chrome rendered {package['title']!r} at {url}; screenshot {destination}", flush=True)
            finally:
                if tab:
                    tab.socket.close()
                browser.terminate()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("screenshot", type=Path)
    parser.add_argument("--move-right", action="store_true")
    args = parser.parse_args()
    check_play(args.url, args.screenshot, args.move_right)
