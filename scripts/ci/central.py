#!/usr/bin/env python3
"""Publish an already verified candidate; uploads are never blindly retried."""
import argparse
import base64
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from pipeline import require, verify_candidate

BASE = "https://central.sonatype.com"


def token():
    return base64.b64encode((os.environ["CENTRAL_USERNAME"] + ":" + os.environ["CENTRAL_PASSWORD"]).encode()).decode()


def request(path, body=b"", content_type="application/json", base=BASE):
    req = urllib.request.Request(base + path, data=body, headers={
        "Authorization": "Bearer " + token(), "Content-Type": content_type}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as response:
        return response.read()


def bundle(candidate, output, key_fingerprint):
    manifest = verify_candidate(candidate)
    require(not output.exists(), "Publication directory already exists")
    output.mkdir(parents=True)
    # GPG homes are ephemeral and never included in artifacts.
    with tempfile.TemporaryDirectory() as gnupg:
        env = os.environ.copy()
        env["GNUPGHOME"] = gnupg
        subprocess.run(["gpg", "--batch", "--import"], input=env["GPG_PRIVATE_KEY"].encode(),
                       env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        keys = subprocess.check_output(["gpg", "--batch", "--with-colons", "--list-secret-keys"], env=env, text=True)
        require(key_fingerprint in [line.split(":")[9] for line in keys.splitlines() if line.startswith("fpr:")], "Signing fingerprint mismatch")
        for name in manifest["files"]:
            shutil.copyfile(candidate / name, output / name)
            subprocess.run(["gpg", "--batch", "--yes", "--pinentry-mode", "loopback", "--passphrase-fd", "0",
                            "--local-user", key_fingerprint, "--armor", "--detach-sign", str(output / name)],
                           input=env["GPG_PASSPHRASE"].encode(), env=env, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["gpg", "--batch", "--verify", str(output / (name + ".asc")), str(output / name)],
                           env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["gpgconf", "--kill", "gpg-agent"], env=env, check=True)
    for path in list(output.iterdir()):
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            (output / (path.name + "." + algorithm)).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())
    prefix = f"{manifest['group'].replace('.', '/')}/{manifest['artifact']}/{manifest['version']}/"
    with zipfile.ZipFile(output / "central-bundle.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.iterdir()):
            if path.suffix != ".zip":
                archive.write(path, prefix + path.name)
    shutil.copyfile(candidate / "manifest.json", output / "manifest.json")
    record = {"sha": manifest["sha"], "version": manifest["version"], "run_id": manifest["run_id"],
              "manifest_sha256": hashlib.sha256((candidate / "manifest.json").read_bytes()).hexdigest(),
              "signing_fingerprint": key_fingerprint,
              "deployment_name": f"ResiCache-{manifest['version']}-{manifest['sha']}",
              "purl": f"pkg:maven/{manifest['group']}/{manifest['artifact']}@{manifest['version']}"}
    (output / "publication.json").write_text(json.dumps(record, indent=2) + "\n")
    return record


def upload(output, base=BASE):
    record_path = output / "publication.json"
    record = json.loads(record_path.read_text())
    require(not record.get("deployment_id"), "This candidate has already been uploaded")
    boundary = "ResiCacheCentralBundle"
    data = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode()
    data += (output / "central-bundle.zip").read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    # A timeout may follow a successful remote upload: do not repeat this POST.
    deployment_id = request("/api/v1/publisher/upload?" + urllib.parse.urlencode({
        "name": record["deployment_name"], "publishingType": "AUTOMATIC"}), data,
        f"multipart/form-data; boundary={boundary}", base).decode().strip()
    require(re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", deployment_id), "Invalid deployment ID")
    record["deployment_id"] = deployment_id
    record_path.write_text(json.dumps(record, indent=2) + "\n")
    return record


def poll(record, base=BASE, timeout=1800, interval=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        # Status is read-only despite using POST. Transient errors are bounded by the deadline.
        try:
            status = json.loads(request("/api/v1/publisher/status?" + urllib.parse.urlencode({"id": record["deployment_id"]}), base=base))
        except urllib.error.HTTPError as error:
            require(error.code == 429 or error.code >= 500, f"Central status request failed: {error.code}")
            time.sleep(interval)
            continue
        except (urllib.error.URLError, TimeoutError):
            time.sleep(interval)
            continue
        require(status.get("deploymentId") == record["deployment_id"], "Deployment identity mismatch")
        require(status.get("deploymentName") == record["deployment_name"], "Deployment name mismatch")
        state = status.get("deploymentState")
        print("Central state:", state, flush=True)
        if state == "PUBLISHED":
            require(record["purl"] in status.get("purls", []), "Published coordinates mismatch")
            return status
        require(state in ("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"), f"Central did not publish: {state}")
        time.sleep(interval)
    raise ValueError("Central publication timed out; resume using the saved deployment ID")


def recovery(candidate, output, deployment_id):
    manifest = verify_candidate(candidate)
    record = json.loads((output / "publication.json").read_text())
    require(record.get("deployment_id", deployment_id) == deployment_id, "Recovery deployment ID mismatch")
    require(re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", deployment_id), "Invalid recovery deployment ID")
    record["deployment_id"] = deployment_id
    require(record["sha"] == manifest["sha"] and record["version"] == manifest["version"]
            and record["run_id"] == manifest["run_id"], "Recovery candidate mismatch")
    require(record["manifest_sha256"] == hashlib.sha256((candidate / "manifest.json").read_bytes()).hexdigest(), "Recovery manifest mismatch")
    require(record["purl"] == f"pkg:maven/{manifest['group']}/{manifest['artifact']}@{manifest['version']}", "Recovery coordinates mismatch")
    require(record["deployment_name"] == f"ResiCache-{manifest['version']}-{manifest['sha']}", "Recovery deployment name mismatch")
    for name, expected in manifest["files"].items():
        require(hashlib.sha256((output / name).read_bytes()).hexdigest() == expected, "Recovery artifact mismatch")
        require((output / (name + ".asc")).is_file(), "Missing recovered signature")
        signature = output / (name + ".asc")
        require(hashlib.sha256(signature.read_bytes()).hexdigest() ==
                (output / (name + ".asc.sha256")).read_text(), "Recovered signature checksum mismatch")
    return record


def verify_published(candidate, base="https://repo.maven.apache.org/maven2", timeout=300):
    manifest = verify_candidate(candidate)
    prefix = f"{manifest['group'].replace('.', '/')}/{manifest['artifact']}/{manifest['version']}"
    deadline = time.monotonic() + timeout
    for name, expected in manifest["files"].items():
        while True:
            try:
                with urllib.request.urlopen(f"{base}/{prefix}/{name}", timeout=30) as response:
                    value = hashlib.sha256(response.read()).hexdigest()
                require(value == expected, f"Published bytes differ from verified candidate: {name}")
                break
            except urllib.error.HTTPError as error:
                require(error.code in (404, 429) or error.code >= 500, f"Published artifact check failed: {error.code}")
                require(time.monotonic() < deadline, "Published artifacts not yet resolvable; resume later")
                time.sleep(10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("bundle", "upload", "poll", "recover", "verify-published"))
    parser.add_argument("--candidate", type=Path, default=Path("candidate"))
    parser.add_argument("--output", type=Path, default=Path("publication"))
    parser.add_argument("--deployment-id")
    args = parser.parse_args()
    if args.command == "bundle":
        bundle(args.candidate, args.output, os.environ["GPG_FINGERPRINT"])
    elif args.command == "upload":
        record = upload(args.output)
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write("deployment_id=" + record["deployment_id"] + "\n")
    elif args.command == "verify-published":
        verify_published(args.candidate)
    else:
        record = recovery(args.candidate, args.output, args.deployment_id) if args.command == "recover" else json.loads((args.output / "publication.json").read_text())
        status = poll(record)
        (args.output / "publication.json").write_text(json.dumps(record, indent=2) + "\n")
        (args.output / "central-status.json").write_text(json.dumps(status, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        # Do not print HTTP bodies, signing subprocess diagnostics or credentials.
        print(f"::error::Publication failed ({type(error).__name__}): {error}", file=sys.stderr)
        sys.exit(1)
