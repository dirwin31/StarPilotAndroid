#!/usr/bin/env python3
"""Capture and scrub the existing demo endpoint allowlist over authenticated LAN HTTP."""
import argparse
import http.cookiejar
import ipaddress
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/demo/api'
PRIVATE = re.compile(r'(dongle|serial|slug|token|password|secret|latitude|longitude|^lat$|^lon$|^lng$|ssid|address|phone|^home$|^work$)', re.I)
SENSITIVE = re.compile(r'(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|(?<![0-9a-f])[0-9a-f]{16}(?![0-9a-f])')

def scrub(value):
    if isinstance(value, dict):
        return {key: (False if isinstance(item, bool) else [] if isinstance(item, list) else None) if PRIVATE.search(key) else scrub(item) for key, item in value.items()}
    if isinstance(value, list):
        return [scrub(item) for item in value]
    if isinstance(value, str):
        return SENSITIVE.sub('demo', value)
    return value

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Redirects are not allowed')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--origin', required=True)
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.origin)
    ip = ipaddress.ip_address(url.hostname or '')
    if url.scheme != 'http' or url.port != 8082 or not isinstance(ip, ipaddress.IPv4Address) or not any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')) or url.username or url.path not in ('', '/'):
        raise ValueError('Use a direct private IPv4 Galaxy origin on port 8082')
    origin = args.origin.rstrip('/')
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    with client.open(origin + '/api/auth/session', timeout=5) as response:
        session = json.load(response)
    if not session.get('authenticated') or not session.get('localAccess'):
        raise ValueError('Direct LAN session required')
    for path in ROOT.rglob('*.json'):
        with client.open(origin + '/api/' + str(path.relative_to(ROOT)).removesuffix('.json'), timeout=5) as response:
            raw = response.read(131073)
        if len(raw) > 131072:
            raise ValueError('Fixture too large')
        path.write_text(json.dumps(scrub(json.loads(raw)), indent=2) + '\n')

if __name__ == '__main__':
    main()
