# CorsairPlay API

Python 3 standard-library service behind the existing Apache HTTPS virtual host.

## Endpoints

- `GET /api/health`
- `GET /api/activate?device_id=...`
- `POST /api/activate` with `{"device_id":"...","code":"..."}`; returns `upload_token` on success
- `POST /api/diagnostics` with `text/plain` body and `Authorization: Bearer <token>`
- `GET /api/updates/manifest.json`
- `GET /api/updates/<file>`

## Server files

- Code: `/opt/corsairplay-server`
- Config: `/etc/corsairplay/config.json` (mode 600)
- Data: `/var/lib/corsairplay`
- Reports: `/var/lib/corsairplay/reports/YYYYMMDD/*.txt`
- Manifest and APKs: `/var/lib/corsairplay/apks`
- Apache proxy: `/etc/apache2/conf-available/corsairplay-api.conf`
- Service: `corsairplay-api.service`

## Administration

```sh
python3 /opt/corsairplay-server/manage.py gen-code --count 10
python3 /opt/corsairplay-server/manage.py list-codes
python3 /opt/corsairplay-server/manage.py list-activations
python3 /opt/corsairplay-server/manage.py revoke DEVICE_ID
/opt/corsairplay-server/publish-apk.sh /path/to/CorsairPlay-0.2.25.apk 0.2.25
```

The upload token is stored only in `/etc/corsairplay/config.json`.
Keep that file mode `0600` and owned by `corsairplay:corsairplay` so the service can read it.
