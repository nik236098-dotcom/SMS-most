#!/usr/bin/env python3
"""Fetch the manufacturer's explicitly public community signing key, never a user's key."""
import argparse, pathlib, re, subprocess, urllib.request
p=argparse.ArgumentParser();p.add_argument('--directory',default='vendor/community');a=p.parse_args()
dest=pathlib.Path(a.directory);dest.mkdir(parents=True,exist_ok=True)
base='https://raw.githubusercontent.com/9esim/9eSIMCommunityKey/main/'
for name in ['9eSIMCommunityKey.jks','LICENSE','info.txt']:
    with urllib.request.urlopen(base+name,timeout=30) as r: (dest/name).write_bytes(r.read())
out=subprocess.check_output(['keytool','-list','-v','-keystore',str(dest/'9eSIMCommunityKey.jks'),'-storepass','147258369'],text=True,env={**__import__('os').environ,'LC_ALL':'C'})
sha=re.findall(r'SHA256:\s*([0-9A-F:]{95})',out)
if len(sha)!=1:raise SystemExit('Unexpected key layout. Inspect official info.txt; no guessed key accepted.')
pin=sha[0].replace(':','').lower();(dest/'sha256.txt').write_text(pin)
print(pin)
