#!/usr/bin/env python3
from pathlib import Path
import re

for path in list(Path('.github/workflows').glob('*.yml')) + list(Path('.github/actions').rglob('action.yml')):
    for reference in re.findall(r'uses:\s*[\x22\x27]?([^\s\x22\x27]+)', path.read_text()):
        if not reference.startswith('./') and not re.fullmatch(r'[^@]+@[0-9a-f]{40}', reference):
            raise ValueError(f'{path}: action is not SHA-pinned: {reference}')
print('All external Actions are SHA-pinned')

verification = Path('.github/workflows/_verify.yml').read_text().split('jobs:\n', 1)[1]
jobs = set(re.findall(r'^  ([\w-]+):$', verification, re.M)) - {'verification-ok'}
gate = verification.split('  verification-ok:', 1)[1]
needs = set(re.search(r'needs: \[([^]]+)\]', gate)[1].replace(',', ' ').split())
expected = set(re.search(r'EXPECTED_JOBS: ([^\n]+)', gate)[1].split())
if jobs != needs or jobs != expected:
    raise ValueError('Every verification job must be included in the blocking gate')
