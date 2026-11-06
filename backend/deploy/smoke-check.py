#!/usr/bin/env python3
"""Check the private deployment without displaying session/CSRF values."""
import http.client
import json
import sys
from http.cookies import SimpleCookie
from urllib.parse import urlsplit

base = urlsplit(sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8080')
if base.scheme != 'http' or base.hostname not in {'localhost', '127.0.0.1'}:
    raise SystemExit('Use the loopback endpoint or SSH tunnel (HTTP localhost only).')

def request(method, path, expected, body=None, headers=None):
    connection = http.client.HTTPConnection(base.hostname, base.port or 80, timeout=15)
    actual_headers = dict(headers or {})
    payload = None
    if body is not None:
        payload = json.dumps(body)
        actual_headers['Content-Type'] = 'application/json'
    connection.request(method, path, payload, actual_headers)
    response = connection.getresponse()
    data = json.loads(response.read())
    response_headers = {k.lower(): v for k, v in response.getheaders()}
    connection.close()
    assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}'
    if path != '/actuator/health':
        assert 'no-store' in response_headers.get('cache-control', ''), path
    if expected >= 400:
        assert response_headers.get('content-type', '').startswith('application/problem+json'), path
        assert data['status'] == expected, path
    print(f'{method} {path}: {response.status} OK')
    return data, response_headers

health, _ = request('GET', '/actuator/health', 200)
assert health == {'status': 'UP'}, 'Health must not expose details'
csrf, response_headers = request('GET', '/api/auth/csrf', 200)
assert csrf['headerName'] == 'X-CSRF-TOKEN' and csrf['token']
cookies = SimpleCookie()
cookies.load(response_headers['set-cookie'])
assert cookies['JSESSIONID']['secure'] and cookies['JSESSIONID']['httponly']
assert cookies['JSESSIONID']['samesite'].lower() == 'lax'
headers = {'Cookie': 'JSESSIONID=' + cookies['JSESSIONID'].value, csrf['headerName']: csrf['token']}
request('GET', '/api/auth/me', 401)
request('GET', '/api/tasks', 401)
request('POST', '/api/tasks', 403, {'title': 'Must not be created'})
request('POST', '/api/tasks', 401, {'title': 'Must not be created'}, headers)
mail, _ = request('POST', '/api/auth/register', 503,
                  {'email': 'deployment-check@example.invalid', 'password': 'not-a-real-user-password'}, headers)
assert mail['code'] == 'mail_delivery_unavailable'
google, _ = request('GET', '/oauth2/authorization/google', 503)
assert google['code'] == 'google_unavailable'
print('Cookie flags, CSRF, authorization, disabled mail and Google verified; no secrets displayed.')
