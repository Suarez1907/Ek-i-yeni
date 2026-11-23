#!/usr/bin/env python3
"""Generate a stable PKCS12 debug keystore and print it as base64.

The generated file is stored in the repository as
`keystore/debug.keystore.b64` so that every CI build signs the APK with the
same key (upgrades stay installable).
"""
import base64
import os
import subprocess
import sys
import tempfile

OUT_DIR = os.environ.get("CODER_OUTPUT_DIR", "coder-out")


def main() -> int:
    tmp = tempfile.mkdtemp()
    key = os.path.join(tmp, "key.pem")
    crt = os.path.join(tmp, "crt.pem")
    p12 = os.path.join(tmp, "debug.keystore")

    subprocess.run(
        [
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", key, "-out", crt, "-days", "10000",
            "-subj", "/CN=Android Debug/O=Android/C=US",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    subprocess.run(
        [
            "openssl", "pkcs12", "-export", "-name", "androiddebugkey",
            "-inkey", key, "-in", crt, "-out", p12,
            "-passout", "pass:android", "-macalg", "sha256",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    data = open(p12, "rb").read()
    b64 = base64.b64encode(data).decode("ascii")
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "debug.keystore.b64"), "w") as fh:
        fh.write(b64 + "\n")

    # Print in fixed-size chunks so the output can be copied verbatim.
    lines = [b64[i:i + 76] for i in range(0, len(b64), 76)]
    print("CHUNK_LINES=%d" % len(lines))
    print("CHUNK_LEN=%d" % len(b64))
    for line in lines:
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())