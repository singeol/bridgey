#!/usr/bin/env python3
"""Build a multi-resolution ICNS file without requiring a full Xcode install."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


REPRESENTATIONS = (
    ("icp4", "icon_16x16.png", 16),
    ("icp5", "icon_16x16@2x.png", 32),
    ("icp6", "icon_32x32@2x.png", 64),
    ("ic07", "icon_128x128.png", 128),
    ("ic08", "icon_128x128@2x.png", 256),
    ("ic09", "icon_256x256@2x.png", 512),
    ("ic10", "icon_512x512@2x.png", 1024),
)


def png_dimensions(data: bytes) -> tuple[int, int]:
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("not a PNG image")
    return struct.unpack(">II", data[16:24])


def build(iconset: Path, output: Path) -> None:
    chunks: list[bytes] = []
    for kind, filename, expected_size in REPRESENTATIONS:
        source = iconset / filename
        data = source.read_bytes()
        dimensions = png_dimensions(data)
        if dimensions != (expected_size, expected_size):
            raise ValueError(
                f"{source} is {dimensions[0]}x{dimensions[1]}, expected "
                f"{expected_size}x{expected_size}"
            )
        chunks.append(kind.encode("ascii") + struct.pack(">I", len(data) + 8) + data)

    payload = b"".join(chunks)
    output.write_bytes(b"icns" + struct.pack(">I", len(payload) + 8) + payload)


def validate(output: Path) -> None:
    data = output.read_bytes()
    if data[:4] != b"icns" or struct.unpack(">I", data[4:8])[0] != len(data):
        raise ValueError(f"invalid ICNS header in {output}")

    offset = 8
    kinds: list[str] = []
    while offset < len(data):
        kind = data[offset : offset + 4].decode("ascii")
        length = struct.unpack(">I", data[offset + 4 : offset + 8])[0]
        if length < 8 or offset + length > len(data):
            raise ValueError(f"invalid {kind} chunk in {output}")
        kinds.append(kind)
        offset += length

    expected = [representation[0] for representation in REPRESENTATIONS]
    if kinds != expected:
        raise ValueError(f"ICNS representations are {kinds}, expected {expected}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("iconset", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.iconset, args.output)
    validate(args.output)


if __name__ == "__main__":
    main()
