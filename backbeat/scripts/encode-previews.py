"""Prints the emulator screenshots as base64 JPEG contact sheets in the job log.

Some review setups can read job logs but cannot download artifacts; this lets
them see the screenshots anyway. Decode a block between the BEGIN/END markers
(after stripping the log timestamps) to get the JPEG back.

Usage: python encode-previews.py <screenshots dir>
"""
import base64
import io
import sys
from pathlib import Path

from PIL import Image


def sheet(paths, thumb_width, columns):
    images = [Image.open(p).convert("RGB") for p in paths]
    thumbs = [im.resize((thumb_width, round(im.height * thumb_width / im.width)), Image.LANCZOS) for im in images]
    cell_h = max(t.height for t in thumbs)
    rows = (len(thumbs) + columns - 1) // columns
    out = Image.new("RGB", (columns * thumb_width + (columns - 1) * 8, rows * cell_h + (rows - 1) * 8), "white")
    for i, t in enumerate(thumbs):
        out.paste(t, ((i % columns) * (thumb_width + 8), (i // columns) * (cell_h + 8)))
    return out


def emit(name, image, max_width):
    if image.width > max_width:
        image = image.resize((max_width, round(image.height * max_width / image.width)), Image.LANCZOS)
    buffer = io.BytesIO()
    image.save(buffer, "JPEG", quality=74, optimize=True)
    data = base64.b64encode(buffer.getvalue()).decode()
    print(f"=== BEGIN {name} ===")
    for i in range(0, len(data), 1000):
        print(data[i:i + 1000])
    print(f"=== END {name} ===")


def main(root):
    root = Path(root)
    shots = sorted(root.glob("*.png"))
    if shots:
        for n, start in enumerate(range(0, len(shots), 6)):
            emit(f"screens-{n + 1}.jpg", sheet(shots[start:start + 6], 360, 3), 1100)
    for png in sorted((root / "gallery").glob("*.png")):
        emit(f"{png.stem}.jpg", Image.open(png).convert("RGB"), 1100)
    for txt in sorted((root / "gallery").glob("*.txt")):
        print(f"--- {txt.name}: {txt.read_text().strip()}")


if __name__ == "__main__":
    main(sys.argv[1])
