#!/usr/bin/env python3
"""Decodes the QR samples written by QrTest with ZXing (an independent decoder) and checks
text, version and error-correction level. Needs: pip install zxing-cpp pillow
Usage: check-qr.py [app/build/qr-samples]"""
import base64, sys
from pathlib import Path
from PIL import Image
import zxingcpp

d = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/qr-samples")
ok = True
for line in (d / "expected.tsv").read_text().splitlines():
    name, version, b64 = line.split("\t")
    want = base64.b64decode(b64)
    found = zxingcpp.read_barcodes(Image.open(d / f"{name}.png"), formats=zxingcpp.BarcodeFormat.QRCode)
    if not found:
        print(f"FAIL {name}: not decoded"); ok = False; continue
    r = found[0]
    got = r.bytes if hasattr(r, "bytes") else r.text.encode()
    ec = getattr(r, "ec_level", "?")
    ver = getattr(r, "symbology_identifier", "")
    good = got == want and ec in ("M", "?")
    ok &= good
    print(f"{'ok  ' if good else 'FAIL'} {name}: version {version}, ec {ec}, {len(want)} bytes{'' if got == want else ' MISMATCH'}")
sys.exit(0 if ok else 1)
