#!/usr/bin/env python3
"""Install checksum-pinned official Linux tools, without system configuration."""
import hashlib
import io
from pathlib import Path
import sys
import tarfile
import urllib.request

TOOLS = (
    ("actionlint", "https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz",
     "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8", "actionlint"),
    ("shellcheck", "https://github.com/koalaman/shellcheck/releases/download/v0.11.0/shellcheck-v0.11.0.linux.x86_64.tar.gz",
     "b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6", "shellcheck-v0.11.0/shellcheck"),
)
output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
for name, url, expected, member in TOOLS:
    if (output / name).is_file():
        continue
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("Tool archive checksum mismatch: " + name)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        (output / name).write_bytes(archive.extractfile(member).read())
    (output / name).chmod(0o755)
