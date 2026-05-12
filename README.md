# NamelessIPLogger

---

#### Please be aware, this plugin is licensed under the [PolyForm Noncommercial License 1.0.0](<https://github.com/NanashiTheNameless/NamelessIPLogger/blob/main/LICENSE.md>) and as such cannot be used by servers/proxies that participate in/allow/facilitate any of the following to occur with real-world money:
- **the sale of ranks,**
- **the acceptance of donations tied to perks,**
- **the sale of cosmetics,**
- **the running of ads/sponsorships,**
- **the sale of any form of monetized access,**
- **and/or the generation of any other form of revenue**

---

NamelessIPLogger is a Velocity and Paper plugin for moderation and security auditing that tracks and correlates:

- player UUIDs
- usernames
- IP addresses
- connection and disconnection events
- GeoIP location data from a local database

It is designed for operational moderation workflows where you need historical identity/IP correlation.

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

- `plugins/namelessiplogger/players.tsv`
- `plugins/namelessiplogger/ip_links.tsv`
- `plugins/namelessiplogger/connection_events.tsv`
- `plugins/namelessiplogger/strings.yml` (customizable command/chat messages)
- `plugins/namelessiplogger/telemetry-instance-id.txt` (anonymous telemetry instance UUID, when telemetry is enabled)

Network access behavior:

- No per-player API requests are made for lookups.
- Telemetry is enabled by default and sends anonymous census pings to [NamelessTelemetry](https://github.com/NanashiTheNameless/NamelessTelemetry); set `telemetry.enabled: "false"` to disable it.
- Telemetry shares only a SHA-256 hash of the local telemetry instance ID, the UTC date, project name, and `count=1`. It does not send player UUIDs, usernames, player IPs, connection logs, or GeoIP data.
- Telemetry only runs when its endpoint uses HTTPS.
- Before each telemetry send, the endpoint host is resolved and private, link-local, loopback, any-local, multicast, and IPv6 unique-local addresses are denied.
- Update checks are enabled by default and query GitHub Releases for newer stable releases; prereleases and the `nightly` tag are ignored. The update endpoint also requires HTTPS and denies private/link-local/loopback resolved addresses.
- The plugin only downloads GeoIP database files when needed (startup, staleness window, or manual update command).
- DB-IP mode downloads from configured DB-IP URLs.
- MaxMind mode downloads from configured MaxMind permalink template using account-id + license-key basic auth when provided.

Operational notes:

- Treat IP/identity data as sensitive and restrict file access.
- Configure retention/deletion according to your local legal and policy requirements.
- Use `/niplookup reload` after config changes and `/niplookup updatedb` for immediate database refresh.
- Use `/niplookup checkupdates` to manually check GitHub Releases for a newer stable plugin version, even when scheduled update checks are disabled.

## GeoIP Database

On startup, the plugin ensures a local GeoIP database exists under:

- `plugins/namelessiplogger/geoip/`

Provider behavior is controlled via `plugins/namelessiplogger/config.yml`:

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

- `plugins/namelessiplogger/config.yml`
- `plugins/namelessiplogger/strings.yml`

Example:

```yaml
# ============================================================================
# NamelessIPLogger - config.yml
# ============================================================================
# All keys are flat key:value pairs (no nested YAML objects required).
#
# Tips:
# - Keep this file UTF-8 encoded.
# - Apply edits with /niplookup reload (proxy restart is not required).
# - Run /niplookup updatedb to force immediate GeoIP database redownload.
# - Quote values to avoid YAML parser edge cases.

# Config schema version. Do not edit.
# The plugin uses this to migrate older configs while preserving known settings.
config.version: "2"

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

# Command access
# By default, /niplookup commands are console-only.
# Set true to also allow players with namelessiplogger.admin to run them.
# Allowed values: true | false.
commands.allow-admin-permission: "false"

# Update checks
# Checks GitHub Releases for newer stable NamelessIPLogger versions and logs a notice.
# Prereleases and the nightly tag are ignored.
# This does not download or install updates.
# Allowed values: true | false.
updates.check-enabled: "true"
# Interval in hours between update checks. Minimum effective value is 1.
updates.check-interval-hours: "6"

# Telemetry
# Sends anonymous census pings to NamelessTelemetry:
# https://github.com/NanashiTheNameless/NamelessTelemetry
# Shared payload: SHA-256 hash of the instance ID, UTC date, project name, and count=1.
# No player UUIDs, usernames, player IPs, connection logs, or GeoIP data are sent.
# The local instance ID is stored separately in telemetry-instance-id.txt.
# Allowed values: true | false.
telemetry.enabled: "true"
```

To use MaxMind GeoLite2 City:

1. Set `geoip.provider: maxmind-geolite2`
2. Set `geoip.maxmind.account-id: <your_account_id>`
3. Set `geoip.maxmind.license-key: <your_key>`
4. Run `/niplookup reload`
5. Run `/niplookup updatedb` (optional, forces immediate DB redownload)

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
- `commands.allow-admin-permission`: allow players with `namelessiplogger.admin` to use `/niplookup` commands (`false` by default; console-only when disabled).
- `telemetry.enabled`: send anonymous [NamelessTelemetry](https://github.com/NanashiTheNameless/NamelessTelemetry) census pings (`true` by default, set to `false` to disable).
- `updates.check-enabled`: check GitHub Releases for newer stable plugin versions (`true` by default; prereleases and `nightly` are ignored; this does not download or install updates).
- `updates.check-interval-hours`: interval between update checks in hours (`6` by default, minimum effective value is `1`).

Update notifications:

- When an update is found, the plugin logs it to console and sends an in-chat notice to online admins.
- Velocity does not have Bukkit-style OPs, so chat notices are sent to players with `namelessiplogger.update.notify` or `namelessiplogger.admin`.
- Admins who join after an update was found also receive the notice.

Permissions:

- `namelessiplogger.admin`: allows player use of `/niplookup` commands when `commands.allow-admin-permission` is enabled; also receives update notices.
- `namelessiplogger.update.notify`: receives update notices without granting command access.

Config migration:

- `config.version` is managed by the plugin and should not be edited.
- When a missing, invalid, or out-of-date config version is detected at startup or reload, the plugin rewrites it using the current template.
- Missing or invalid known settings also trigger migration.
- Before migration, the previous file is backed up as `config.yml.<schema-version>.bak`; if the schema version is missing or invalid, it is backed up as `config.yml.bak`.
- Valid known settings already present in the old config are preserved; missing or invalid known settings use current defaults.
- Unrecognized settings are kept at the bottom of the file for manual review, but they are not read by the plugin.

Telemetry shared data:

- `id`: SHA-256 hash of `telemetry-instance-id.txt`; the raw local UUID is not sent.
- `date`: current UTC date in `YYYY-MM-DD` format.
- `projectname` / `project`: `NamelessIPLogger`.
- `count`: `1`.
- Request headers include a plugin user agent and `X-Project-Name: NamelessIPLogger`.

## Translation

Command output and update notices can be translated in:

- `plugins/namelessiplogger/strings.yml`

Run `/niplookup reload` after editing the file. Keep placeholder names such as `{uuid}`, `{error}`, `{permission}`, `{current}`, `{latest}`, and `{url}` intact.

## Stored Data

The plugin stores data in:

- `plugins/namelessiplogger/players.tsv`
- `plugins/namelessiplogger/ip_links.tsv`
- `plugins/namelessiplogger/connection_events.tsv`

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

Quality checks:

```bash
./gradlew spotlessCheck
./gradlew test jacocoTestReport
```

Output jar:

- `build/libs/`

## Installation

1. Download/Build the plugin jar.
2. Place the jar in your server `plugins/` folder.
3. Start or restart your platform.
4. Verify startup logs mention NamelessIPLogger initialization.

Platform paths:

- Velocity: `plugins/`
- Paper: `plugins/`

## Console Commands

These commands are console-only by default. Set `commands.allow-admin-permission: "true"` to also allow players with `namelessiplogger.admin` to use them.

- `/niplookup uuid <uuid>`
- `/niplookup username <username>`
- `/niplookup ip <ip>`
- `/niplookup reload`
- `/niplookup updatedb`
- `/niplookup checkupdates`

Aliases:

- `/niplog`
- `/iplookup`
- `/iplog`

Additional subcommand aliases are accepted:

- `user`, `name`
- `update-db`, `refreshdb`
- `checkupdate`, `updatecheck`, `update-check`, `updates`

Examples:

```text
/niplookup uuid 00000000-0000-0000-0000-000000000000
/niplookup username SomePlayer
/niplookup ip 203.0.113.10
/niplookup reload
/niplookup updatedb
/niplookup checkupdates
```

## Nightly Auto-Builds

Nightly development builds are published automatically from the latest `main` or `master` branch changes, and can also be triggered manually.

- `https://github.com/NanashiTheNameless/NamelessIPLogger/releases/tag/nightly`

Nightly builds are intended for testing and may change more frequently than stable versioned releases. Built-in update checks ignore prereleases and the `nightly` tag.

## Notes

- Private/local IPs are marked as `private` and not geolocated.
- If GeoIP DB initialization fails, the plugin still logs correlations and connection events, but GeoIP data will be `unavailable` until DB loading succeeds.

## Namespace

- Java package: `dev.namelessnanashi.namelessiplogger`
- Plugin ID: `namelessiplogger`

## License

This project is licensed under **PolyForm Noncommercial 1.0.0**.

- License file: `LICENSE.md`
- Canonical text: `https://polyformproject.org/licenses/noncommercial/1.0.0`
