# VelocityIPLogger

VelocityIPLogger is a Velocity proxy plugin that tracks and correlates:

- player UUIDs
- usernames
- IP addresses
- connection and disconnection events
- GeoIP location data from a local database

It is designed for moderation and security auditing workflows where you need historical identity/IP correlation.

## Features

- Correlates player identity data over time (`UUID <-> username <-> IP`).
- Logs connection lifecycle events (`CONNECT` and `DISCONNECT`) with session IDs.
- Records session duration on disconnect when a matching session exists.
- Performs GeoIP lookups from local MMDB databases (no per-request external API calls).
- Uses Country, City, and ASN enrichment by default.
- Caches GeoIP lookups in memory for improved performance.
- Persists data in TSV files under the plugin data folder for easy inspection and export.

## Privacy and Network Disclosure

This plugin is intended for server moderation and abuse investigation workflows.

Data collected (configurable):

- UUID
- username
- IP address
- connection/disconnection timestamps
- optional GeoIP enrichment (country/region/city/asn)

Data storage location:

- `plugins/velocityiplogger/players.tsv`
- `plugins/velocityiplogger/ip_links.tsv`
- `plugins/velocityiplogger/connection_events.tsv`

Network access behavior:

- No per-player API requests are made for lookups.
- The plugin only downloads GeoIP database files when needed (startup, staleness window, or manual update command).
- DB-IP mode downloads from configured DB-IP URLs.
- MaxMind mode downloads from configured MaxMind permalink template using account-id + license-key basic auth when provided.

Operational notes:

- Treat IP/identity data as sensitive and restrict file access.
- Configure retention/deletion according to your local legal and policy requirements.
- Use `/viplookup reload` after config changes and `/viplookup updatedb` for immediate database refresh.

## GeoIP Database

On startup, the plugin ensures a local GeoIP database exists under:

- `plugins/velocityiplogger/geoip/`

Provider behavior is controlled via `plugins/velocityiplogger/config.yml`:

- `geoip.provider=dbip` uses DB-IP City + ASN Lite downloads.
- `geoip.provider=maxmind-geolite2` uses MaxMind GeoLite2 City + ASN downloads.

If the database file is missing or older than `geoip.refresh-days`, it downloads a fresh copy.

Default DB-IP source:

- `https://download.db-ip.com/free/dbip-city-lite-YYYY-MM.mmdb.gz`

MaxMind source (requires license key):

- `https://download.maxmind.com/geoip/databases/GeoLite2-City/download?suffix=tar.gz`

After the database is present, lookups are done locally against the MMDB file.

## Configuration

The plugin writes a default config at first startup:

- `plugins/velocityiplogger/config.yml`

Example:

```yaml
# ============================================================================
# VelocityIPLogger - config.yml
# ============================================================================
# All keys are flat key:value pairs (no nested YAML objects required).
#
# Tips:
# - Keep this file UTF-8 encoded.
# - Apply edits with /viplookup reload (proxy restart is not required).
# - Run /viplookup updatedb to force immediate GeoIP database redownload.
# - Quote values to avoid YAML parser edge cases.

# GeoIP provider: dbip | maxmind-geolite2
# Provider guidance:
# - dbip: easiest setup, no license key required.
# - maxmind-geolite2: requires a MaxMind key, generally better data quality.
geoip.provider: "dbip"

# Redownload interval in days (minimum effective value is 1)
# Lower values update sooner but increase network usage.
geoip.refresh-days: "7"

# DB-IP city and ASN sources (.mmdb.gz)
# YYYY-MM is expanded at runtime to the current year-month.
# The configured URL remains the source of truth.
# If download fails, plugin only tries .mmdb/.mmdb.gz extension fallback.
# Compatibility: URLs using -latest also try current/recent monthly filenames.
geoip.dbip.url: "https://download.db-ip.com/free/dbip-city-lite-YYYY-MM.mmdb.gz"
geoip.dbip.asn-url: "https://download.db-ip.com/free/dbip-asn-lite-YYYY-MM.mmdb.gz"
# If you host your own mirror, point these URLs to your mirror endpoints.

# MaxMind GeoLite2 provider settings
# License key from https://www.maxmind.com/
# Required for maxmind-geolite2.
# Account ID is optional but recommended for direct-download basic auth.
geoip.maxmind.account-id: ""
geoip.maxmind.license-key: ""
# Keep this key secret; do not share it in logs or screenshots.
# City edition id. Usually keep as GeoLite2-City.
geoip.maxmind.edition-id: "GeoLite2-City"
# ASN edition id. Usually keep as GeoLite2-ASN.
geoip.maxmind.asn-edition-id: "GeoLite2-ASN"
# Download URL template used for MaxMind edition downloads.
# Placeholders:
#   {edition_id}  -> replaced with edition id
#   {license_key} -> replaced with license key (optional compatibility)
# If account-id is set, basic auth is sent as account-id:license-key.
geoip.maxmind.url-template: "https://download.maxmind.com/geoip/databases/{edition_id}/download?suffix=tar.gz"

# Logging controls
# Privacy note: these toggles affect what is written to disk.
# Warning: disabling IP logging can reduce correlation usefulness.
# Include username in stored records/events.
log.include-username: "true"
# Include IP in stored records/events.
log.include-ip: "true"
# Perform and store GeoIP enrichment details.
log.include-geoip: "true"
# Write/update players.tsv
log.write-players: "true"
# Write/update ip_links.tsv
log.write-ip-links: "true"
# Append to connection_events.tsv
log.write-connection-events: "true"
# Maximum age (in days) to retain log rows based on event/last_seen time.
# 0 disables retention pruning.
log.max-retention-days: "0"
# Print connect logs to proxy console
log.console-connect: "true"
# Print disconnect logs to proxy console
log.console-disconnect: "true"
```

To use MaxMind GeoLite2 City:

1. Set `geoip.provider: maxmind-geolite2`
2. Set `geoip.maxmind.account-id: <your_account_id>`
3. Set `geoip.maxmind.license-key: <your_key>`
4. Run `/viplookup reload`
5. Run `/viplookup updatedb` (optional, forces immediate DB redownload)

URL notes:

- `geoip.dbip.url` controls the DB-IP city MMDB source.
- `geoip.dbip.asn-url` controls the DB-IP ASN MMDB source.
- `geoip.maxmind.url-template` controls MaxMind download URLs.
- `geoip.maxmind.account-id` + `geoip.maxmind.license-key` are used as HTTP Basic Auth for MaxMind downloads.
- DB-IP downloads use the configured URLs as the source of truth.
- If a DB-IP URL contains `YYYY-MM`, the plugin expands it at runtime to the current month.
- DB-IP URLs are not rewritten beyond `YYYY-MM` expansion (except `.gz`/plain `.mmdb` fallback).
- If a DB-IP URL still uses `-latest`, the plugin also tries current/recent monthly filenames as compatibility fallback.

Logging option notes:

- `log.include-username`: include username in stored records.
- `log.include-ip`: include IP in stored records.
- `log.include-geoip`: include GeoIP enrichment in stored records.
- `log.write-players`: write/update `players.tsv`.
- `log.write-ip-links`: write/update `ip_links.tsv`.
- `log.write-connection-events`: append to `connection_events.tsv`.
- `log.max-retention-days`: prune rows older than this many days using event/last_seen timestamps (`0` disables pruning).
- `log.console-connect`: write connect events to proxy console log.
- `log.console-disconnect`: write disconnect events to proxy console log.

## Stored Data

The plugin stores data in:

- `plugins/velocityiplogger/players.tsv`
- `plugins/velocityiplogger/ip_links.tsv`
- `plugins/velocityiplogger/connection_events.tsv`

### `players.tsv`

Tracks the latest known identity snapshot per UUID:

- `uuid`
- `username`
- `first_seen`
- `last_seen`
- `last_ip`
- `known_ips` (comma-separated IP history)
- `known_ips_count` (number of unique known IPs)

### `ip_links.tsv`

Tracks correlation between UUID and IP with enrichment:

- `uuid`
- `ip`
- `first_seen`
- `last_seen`
- `times_seen`
- `country_code`
- `country`
- `region`
- `city`
- `timezone`
- `isp`
- `asn_number`
- `asn_org`
- `lat`
- `lon`
- `geo_status`
- `geo_message`
- `geo_summary` (human-readable one-line geo summary)

### `connection_events.tsv`

Append-only event log:

- `event_time`
- `event_type`
- `session_id`
- `uuid`
- `username`
- `ip`
- `duration_seconds`
- `reason`
- `duration_human` (formatted as `Xh Ym Zs`)

## Build

Requirements:

- Java 21+
- Gradle (or use the included wrapper)

Build command:

```bash
./gradlew clean
./gradlew build
```

Output jar:

- `build/libs/`

## Installation

1. Download/Build the plugin jar.
2. Place the jar in your Velocity `plugins/` folder.
3. Start or restart the proxy.
4. Verify startup logs mention VelocityIPLogger initialization.

## Console Commands

These commands are console-only and cannot be executed by players.

- `/viplookup uuid <uuid>`
- `/viplookup username <username>`
- `/viplookup ip <ip>`
- `/viplookup reload`
- `/viplookup updatedb`

Aliases:

- `/viplog`
- `/iplookup`
- `/iplog`

Examples:

```text
/viplookup uuid 00000000-0000-0000-0000-000000000000
/viplookup username SomePlayer
/viplookup ip 203.0.113.10
/viplookup reload
/viplookup updatedb
```

## Notes

- Private/local IPs are marked as `private` and not geolocated.
- If GeoIP DB initialization fails, the plugin still logs correlations and connection events, but GeoIP data will be `unavailable` until DB loading succeeds.

## Namespace

- Java package: `dev.namelessnanashi.velocityiplogger`
- Plugin ID: `velocityiplogger`

## License

This project is licensed under **PolyForm Noncommercial 1.0.0**.

- License file: `LICENSE.md`
- Canonical text: `https://polyformproject.org/licenses/noncommercial/1.0.0`
