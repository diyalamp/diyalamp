"""Losslessly recompress PNG representations produced by macOS iconutil."""

import argparse
import os
from pathlib import Path
import stat
import struct
import tempfile
import zlib


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_FILTERED_BYTES = 32 * 1024 * 1024


def png_chunks(png):
    offset = len(PNG_SIGNATURE)
    while offset < len(png):
        if len(png) - offset < 12:
            raise ValueError("Truncated PNG chunk")
        size, kind = struct.unpack_from(">I4s", png, offset)
        end = offset + 8 + size
        if end + 4 > len(png):
            raise ValueError("Invalid PNG chunk size")
        data = png[offset + 8:end]
        checksum, = struct.unpack_from(">I", png, end)
        if zlib.crc32(data, zlib.crc32(kind)) != checksum:
            raise ValueError("Invalid PNG checksum")
        yield kind, data
        offset = end + 4


def compact_png(png):
    chunks = list(png_chunks(png))
    kinds = [kind for kind, _ in chunks]
    if not kinds or kinds[0] != b"IHDR" or kinds[-1] != b"IEND" or b"IDAT" not in kinds:
        raise ValueError("Incomplete PNG")
    first = kinds.index(b"IDAT")
    last = len(kinds) - kinds[::-1].index(b"IDAT")
    if any(kind != b"IDAT" for kind in kinds[first:last]):
        raise ValueError("Noncontiguous PNG image data")
    stream = b"".join(data for _, data in chunks[first:last])
    decoder = zlib.decompressobj()
    filtered = decoder.decompress(stream, MAX_FILTERED_BYTES + 1)
    if len(filtered) > MAX_FILTERED_BYTES or not decoder.eof or decoder.unused_data:
        raise ValueError("Invalid or oversized PNG image data")
    chunks[first:last] = [(b"IDAT", zlib.compress(filtered, level=9))]

    output = bytearray(PNG_SIGNATURE)
    for kind, data in chunks:
        output.extend(struct.pack(">I4s", len(data), kind))
        output.extend(data)
        output.extend(struct.pack(">I", zlib.crc32(data, zlib.crc32(kind))))
    return bytes(output) if len(output) < len(png) else png


def compact_icon(icon):
    if len(icon) < 8 or struct.unpack_from(">4sI", icon) != (b"icns", len(icon)):
        raise ValueError("Invalid ICNS header")
    output = bytearray()
    has_toc = False
    offset = 8
    while offset < len(icon):
        if len(icon) - offset < 8:
            raise ValueError("Truncated ICNS record")
        kind, size = struct.unpack_from(">4sI", icon, offset)
        end = offset + size
        if size < 8 or end > len(icon):
            raise ValueError("Invalid ICNS record size")
        has_toc |= kind == b"TOC "
        data = icon[offset + 8:end]
        if data.startswith(PNG_SIGNATURE):
            data = compact_png(data)
        output.extend(struct.pack(">4sI", kind, len(data) + 8))
        output.extend(data)
        offset = end
    # A table of contents describes the original representation sizes.
    if has_toc:
        return icon
    return struct.pack(">4sI", b"icns", len(output) + 8) + output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("icon", type=Path)
    path = parser.parse_args().icon
    try:
        mode = path.lstat().st_mode
        if not stat.S_ISREG(mode):
            raise ValueError("Icon must be a regular file")
        original = path.read_bytes()
        compact = compact_icon(original)
        if len(compact) < len(original):
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".icon-", delete=False) as temporary:
                staged = Path(temporary.name)
                try:
                    os.fchmod(temporary.fileno(), stat.S_IMODE(mode))
                    temporary.write(compact)
                    temporary.close()
                    os.replace(staged, path)
                finally:
                    staged.unlink(missing_ok=True)
        print(f"Icon size {len(original)} → {len(compact)} bytes")
    except (OSError, ValueError, zlib.error) as error:
        parser.exit(1, f"Could not compact {path}\n{error}\n")


if __name__ == "__main__":
    main()
