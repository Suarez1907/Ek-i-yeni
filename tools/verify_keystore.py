#!/usr/bin/env python3
"""Verify that keystore/debug.keystore.b64 decodes into a usable PKCS12 keystore."""
import base64
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B64 = os.path.join(ROOT, "keystore", "debug.keystore.b64")

raw = open(B64, "rb").read().decode("ascii").strip()
data = base64.b64decode(raw, validate=True)
print("B64_LEN=%d" % len(raw))
print("BIN_LEN=%d" % len(data))

p12 = os.path.join(ROOT, "debug.keystore")
with open(p12, "wb") as fh:
    fh.write(data)

res = subprocess.run(
    ["openssl", "pkcs12", "-in", p12, "-info", "-noout", "-passin", "pass:android"],
    capture_output=True, text=True,
)
print("OPENSSL_RC=%d" % res.returncode)
print(res.stdout[-800:])
print(res.stderr[-800:])
sys.exit(res.returncode)