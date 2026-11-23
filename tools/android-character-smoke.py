"""From photo to playable character through the assistant chat on Android, with real taps (C16b, ADR-0013).

The photo is an original illustrated portrait drawn by this script (no real person). Start a local SpawnKit server in
local mode with OPENAI_API_KEY (and, for SpawnKit AI, ANTHROPIC_API_KEY), its console written to a log file, then
install the current debug APK on an emulator and run:
    python tools/android-character-smoke.py <evidence-directory> <server-log> [--port 8080] [--serial emulator-5554]
This makes real image calls on the server's key: two styles at low quality. Without ANTHROPIC_API_KEY on the server,
the labelled scripted demo answers instead of SpawnKit AI; the images are made by the real image model either way.
Needs Pillow. Starts a new blank game, leaving any saved project file alone.
"""
import argparse
import io
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

sys.stdout.reconfigure(encoding="utf-8")
PROMPT = "Make this person a character in the game."


def portrait(path):
    """An original cartoon portrait: a face with hair, eyes, a smile and a green shirt on a light background."""
    image = Image.new("RGB", (512, 512), (225, 236, 244))
    draw = ImageDraw.Draw(image)
    draw.rectangle((136, 400, 376, 512), fill=(67, 160, 71))
    draw.rectangle((226, 340, 286, 410), fill=(224, 172, 135))
    draw.ellipse((146, 96, 366, 356), fill=(224, 172, 135))
    draw.pieslice((136, 70, 376, 260), 180, 360, fill=(93, 64, 55))
    for x in (206, 306):
        draw.ellipse((x - 16, 206, x + 16, 238), fill=(255, 255, 255))
        draw.ellipse((x - 8, 214, x + 8, 232), fill=(62, 39, 35))
    draw.arc((206, 250, 306, 320), 20, 160, fill=(150, 60, 60), width=6)
    image.save(path)


def run(output, server_log, port, serial):
    if not serial.startswith("emulator-"):
        raise ValueError("Select an emulator.")
    adb = shutil.which("adb") or Path(os.environ["LOCALAPPDATA"], "Android/Sdk/platform-tools/adb.exe")
    output.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex[:8]
    dump = f"/data/local/tmp/spawnkit-character-{token}.xml"
    email = f"ada{token}@example.com"

    def device(*args, binary=False, timeout=55):
        result = subprocess.run([adb, "-s", serial, *args], capture_output=True, check=True, timeout=timeout)
        return result.stdout if binary else result.stdout.decode().strip()

    def nodes():
        for _ in range(8):
            try:
                if "dumped to:" in device("shell", "uiautomator", "dump", dump):
                    return ET.fromstring(device("exec-out", "cat", dump)).findall(".//node")
            except (subprocess.CalledProcessError, ET.ParseError):
                pass  # The tree is briefly unavailable while the UI animates.
            time.sleep(.5)
        raise RuntimeError("Android did not expose its accessibility tree.")

    def bounds(node):
        return list(map(int, re.findall(r"\d+", node.attrib["bounds"])))

    def centre(node):
        x1, y1, x2, y2 = bounds(node)
        return (x1 + x2) // 2, (y1 + y2) // 2

    def tap_node(node):
        x, y = centre(node)
        device("shell", "input", "tap", str(x), str(y))
        time.sleep(.5)

    def label(node, text):
        return any(node.get(attr, "").casefold() == text.casefold() for attr in ("text", "content-desc"))

    def wait(predicate, name, tap=False, last=False, seconds=90, below=0):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            matches = [n for n in nodes() if predicate(n) and bounds(n)[1] >= below]
            if matches:
                node = max(matches, key=lambda n: bounds(n)[1]) if last else matches[0]
                if tap:
                    tap_node(node)
                print(name, flush=True)
                return node
            time.sleep(.5)
        raise RuntimeError(f"Timed out waiting for {name}")

    def scroll_until(predicate, name):
        for _ in range(10):
            snapshot = nodes()
            if any(predicate(n) for n in snapshot):
                return
            scroll = max((n for n in snapshot if n.get("scrollable") == "true"), key=lambda n: bounds(n)[1])
            x1, y1, x2, y2 = bounds(scroll)
            x = str((x1 + x2) // 2)
            device("shell", "input", "swipe", x, str((y1 + 4 * y2) // 5), x, str((y1 + y2) // 2), "400")
            time.sleep(.4)
        raise RuntimeError(f"Could not scroll to {name}")

    def fields():
        return [n for n in nodes() if n.get("class") == "android.widget.EditText"]

    def input_text(node, value, secret=False):
        """Types [value] one character at a time (this keyboard drops fast input), checking visible fields."""
        index = [f.get("bounds") for f in fields()].index(node.get("bounds"))
        for _ in range(3):
            tap_node(fields()[index])
            device("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
            device("shell", "input", "keyevent", "KEYCODE_DEL")
            for char in value:
                device("shell", "input", "text", "%s" if char == " " else char)
            device("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(.4)
            if secret or fields()[index].get("text", "") == value:
                return
        raise RuntimeError(f"Could not type {value!r}")

    def capture(filename):
        data = device("exec-out", "screencap", "-p", binary=True)
        (output / filename).write_bytes(data)
        return Image.open(io.BytesIO(data)).convert("RGB")

    def console_code():
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            raw = server_log.read_bytes()
            # PowerShell's redirection writes UTF-16; shells write UTF-8.
            text = raw.decode("utf-16", errors="replace") if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else raw.decode("utf-8", "replace")
            lines = [line for line in text.splitlines() if f"To: {email}" in line]
            if lines:
                return re.search(r"code: (\d{6})", lines[-1]).group(1)
            time.sleep(.5)
        raise RuntimeError("The server printed no code; is it in local mode?")

    def character(image):
        """The box around the character in the play canvas: pixels that are neither background nor platform."""
        background = image.getpixel((20, 400))
        found = []
        for y in range(240, image.height - 900, 3):
            for x in range(0, image.width, 3):
                r, g, b = image.getpixel((x, y))
                near = lambda c, t: sum(abs(a - z) for a, z in zip(c, t)) < 60
                if not near((r, g, b), background) and not near((r, g, b), (0x8D, 0x6E, 0x63)):
                    found.append((x, y))
        if len(found) < 40:
            return None
        xs, ys = [p[0] for p in found], [p[1] for p in found]
        return min(xs), min(ys), max(xs), max(ys)

    picture = output / "character-portrait.png"
    portrait(picture)
    device("push", str(picture), "/sdcard/Pictures/character-portrait.png")
    device("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d",
           "file:///sdcard/Pictures/character-portrait.png")
    tunnel = int(device("reverse", "tcp:0", f"tcp:{port}"))
    address = f"http://127.0.0.1:{tunnel}"
    try:
        device("shell", "am", "force-stop", "dev.spawnkit")
        device("shell", "am", "start", "-W", "-n", "dev.spawnkit/.MainActivity")
        wait(lambda n: label(n, "Project") or label(n, "Start new"), "Builder open")
        start_new = next((n for n in nodes() if label(n, "Start new")), None)
        if start_new is not None:
            tap_node(start_new)

        # Sign in with a new account on the local server; its emailed code is printed to the console in local mode.
        wait(lambda n: label(n, "Project"), "Project menu", tap=True)
        wait(lambda n: label(n, "Account…"), "Account", tap=True)
        wait(lambda n: label(n, "SpawnKit account"), "Account screen")
        signed_out = next((n for n in nodes() if label(n, "Sign out")), None)
        if signed_out is not None:
            tap_node(signed_out)
            wait(lambda n: label(n, "Create account"), "Signed out")
        input_text(fields()[0], address)
        wait(lambda n: label(n, "Create account"), "Create account tab", tap=True)
        input_text(fields()[1], "Ada")
        input_text(fields()[2], email)
        input_text(fields()[3], "correct horse", secret=True)
        wait(lambda n: label(n, "Create account"), "Create account button", tap=True, last=True)
        wait(lambda n: n.get("text", "").startswith("Check your email"), "Code requested")
        input_text(fields()[1], console_code())
        wait(lambda n: label(n, "Confirm"), "Confirm code", tap=True)
        wait(lambda n: n.get("text", "").startswith("Signed in as Ada"), "Signed in")
        wait(lambda n: label(n, "Back to editor"), "Back to editor", tap=True)

        # A blank game with a platform for the character to stand on.
        wait(lambda n: label(n, "Project"), "Project menu", tap=True)
        wait(lambda n: label(n, "New game…"), "New game", tap=True)
        wait(lambda n: label(n, "Blank"), "Blank", tap=True)
        discard = next((n for n in nodes() if label(n, "Discard changes")), None)
        if discard is not None:
            tap_node(discard)
        wait(lambda n: label(n, "Add"), "Add tab", tap=True, below=1400)
        wait(lambda n: label(n, "Platform"), "Platform", tap=True)

        # Attach the portrait in the chat and ask.
        wait(lambda n: label(n, "AI"), "AI tab", tap=True, below=1400)
        model = wait(lambda n: n.get("text", "").startswith(("Live:", "Scripted demo")), "Model label").get("text")
        scroll_until(lambda n: label(n, "Attach photo"), "Attach photo")
        wait(lambda n: label(n, "Attach photo"), "Attach photo", tap=True)
        time.sleep(2)
        capture("android-character-picker.png")
        # The system photo picker shows the newest picture first; it hides file names.
        photo = wait(
            lambda n: (n.get("content-desc", "").startswith("Photo") and not label(n, "Photos")) or "character-portrait" in n.get("text", ""),
            "Photo",
        )
        tap_node(photo)
        wait(lambda n: n.get("text", "").startswith("Photo attached"), "Photo attached", seconds=30)
        prompt = PROMPT if model.startswith("Scripted demo") else PROMPT + " Put it standing on top of the platform."
        input_text(next(n for n in fields()), prompt)
        scroll_until(lambda n: label(n, "Ask"), "Ask")
        wait(lambda n: label(n, "Ask"), "Ask", tap=True)

        # Nothing is sent before the creator confirms with consent.
        wait(lambda n: n.get("text", "").startswith("Send your photo to the image model"), "Asked before sending", seconds=180)
        scroll_until(lambda n: label(n, "Send photo"), "Send photo")
        consent = wait(lambda n: n.get("checkable") == "true", "Consent checkbox")
        tap_node(consent)
        capture("android-character-confirm.png")
        wait(lambda n: label(n, "Send photo"), "Send photo", tap=True)
        wait(lambda n: label(n, "Accept"), "Proposal", seconds=300)
        scroll_until(lambda n: label(n, "Accept"), "Accept")
        capture("android-character-review.png")
        wait(lambda n: label(n, "Accept"), "Accept", tap=True)
        time.sleep(1)
        capture("android-character-edit.png")

        # Play: the character is drawn, walks right while Right is held, and jumps.
        wait(lambda n: label(n, "▶ Play") or label(n, "Play"), "Play", tap=True)
        time.sleep(2)
        resting = capture("android-character-play.png")
        start = character(resting)
        assert start, "The character is not drawn"
        right = wait(lambda n: label(n, "Right"), "Right control")
        x, y = centre(right)
        device("shell", "input", "swipe", str(x), str(y), str(x), str(y), "400")
        time.sleep(.5)
        walked = character(capture("android-character-walk.png"))
        assert walked and walked[0] > start[0] + 20, f"The character did not walk right: {start} -> {walked}"
        jump = wait(lambda n: label(n, "Jump"), "Jump control")
        x, y = centre(jump)
        device("shell", "input", "tap", str(x), str(y))
        jumped = character(capture("android-character-jump.png"))
        assert jumped and jumped[3] < walked[3] - 20, f"The character did not jump: {walked} -> {jumped}"
        print(f"PASS: {model}; the portrait became a character standing at {start}, walked right to {walked} and "
              f"jumped to {jumped}.", flush=True)
    finally:
        device("reverse", "--remove", f"tcp:{tunnel}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("server_log", type=Path)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    run(args.output, args.server_log, args.port, args.serial)
