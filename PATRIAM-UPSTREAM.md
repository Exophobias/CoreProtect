# CoreProtect upstream preservation

## November 19, 2026 — 25.0

Integrated official `PlayPro/CoreProtect` master `d4716695456885bb098730c9222c1e1027545871`
(271 incoming commits) into the maintained master whose previous head was
`34b5b6a8f30fbe5fd28fe55c6b6a8caa33129770`.

- Retain permission-filtered ordinary/staff help, eight rows per page, guarded direct topics,
  independent command/tab permissions and unknown-command help fallback. Adopt upstream command
  labels and coordinate aliases within that help. The checkout's private `CommandHelpTest` remains
  ignored; published tests cover the new compatibility seams independently.
- Adopt upstream database/consumer/listener/rollback fixes. The DuckDB UUID workaround is
  superseded by upstream's transactional removal of inappropriate unique constraints: repeated
  entity UUID/kill records now survive instead of being discarded with a diagnostic. The new
  `DuckDbEntityUuidMigrationTest` checks preserved rows/sequence, repeated IDs, idempotence and
  rollback of a failed rebuild. Keep `EntityLookupPredicateTest` and its actual database queries.
- Preserve the maintained Maven test/profile/property contract. Paper 26.3 build 226 is the
  compile gate, supporting the checked Development build 226 and Production build 251. The
  release supplies `-Dproject.branch=patriam`; the canonical builder verifies that marker.
  The old `development` marker skipped database migrations and is unsafe for this update. CoreProtect API signatures consumed by Siege remain intact; Siege's Maven
  dependency advances to 25.0.

### Database admission

The upstream artifact still says 25.0 but bundles runtime changes requiring patches 25.1, 25.2
and 26.0. Use the reviewed independent database watermark **2.26.0** for patch selection, initial
schema records, ClickHouse initialization and purge admission. Keep artifact/API version 25.0.
Existing databases run every applicable bundled step through that watermark, including the sign
rollback column and DuckDB UUID constraint removal. The 26.0 MySQL step widens usernames to match
the incoming runtime; it is a DuckDB no-op. New databases record the full current watermark once.
Refuse a future database revision without rewriting its history. `DatabaseWatermarkTest` drives the
actual patch admission and scripts against real DuckDB fixtures starting at 2.24.1 and the older
2.23.1 record produced by historical development-marked builds, plus fresh/future cases.

### Operator configuration schema 1

The upstream parser/configuration update triggers adoption of independently marked `config.yml`,
each world `*.yml`, and `language.yml`. Missing physical markers are schema 0. The explicit 0→1
migration builds from the maintained canonical template, preserves explicit choices and unknown
scalar extensions, and keeps world files sparse so global defaults continue to inherit. Existing
unversioned files without `database-type` retain the legacy `use-mysql` choice (otherwise SQLite);
a genuinely new installation retains upstream's DuckDB default. Credentials are never printed.

CoreProtect historically uses a flat line format. Schema 1 validates that supported scalar format
strictly instead of silently dropping malformed entries. It rejects duplicates, malformed/future
versions, nested structures, merge/alias/tag values, extra documents, invalid UTF-8, invalid known
booleans/integers, and unsafe database identifiers/filenames. Published bare blank password,
donation-key and optional filename/time strings are migrated as explicit empty strings. Empty
language phrases retain the existing bundled-fallback meaning. Language key case normalization
retains CoreProtect's existing case-insensitive phrase behavior. No unknown scalar is discarded.
The `.language` translation cache remains runtime data, without an operator schema.

All operator files and cache bytes prepare before any replacement. Recheck every source and world
file membership, use private temporary candidates and mandatory atomic replacement, then reread and
reparse installed bytes before publishing one global/world/language/blacklist generation. Existing `blacklist.txt` syntax is
unchanged; its exact source is included in preparation and reload admission without rewriting it. A partial multi-file
migration is safe to repeat: each successfully installed schema-1 member is byte-stable and the
remaining schema-0 members complete on the next attempt. No backups are created. Old database-patch
language cleanup cannot rewrite a schema-managed operator catalogue.

`/co reload` retains active database clients and pending writes. Database type/connection/prefix,
resource and locking changes require a server restart; they are rejected before configuration
migration or publication during reload. Other invalid/replaced-during-read files likewise retain
the prior configuration, phrases, clients and network workers. Startup retains upstream database
initialization and database migrations after configuration admission. This replaces upstream's
reload path that closed a healthy database before all validation completed.

Regression anchors: `OperatorConfigurationTest`, `ReloadConfigurationFailureTest`,
`UpstreamHelpCompatibilityTest`, `DuckDbEntityUuidMigrationTest`, `DatabaseWatermarkTest`,
`EntityLookupPredicateTest`.
Focused and canonical test/build, publication and server receipts are recorded separately in the
shared November 19 upstream handoff; source integration alone does not establish live activation.

November 19 focused evidence: native Java 25 / Paper 26.3 build 226,
`mvn -nsu -Dmaven.repo.local=G:/Git/.tools/m2-repository -Ppaper-26.3 -Dproject.branch=patriam clean test`
passed **53 tests, no failures/errors/skips** (46 published cases plus 7 existing private help cases).
Read-only production and Development probes streamed operator files into an in-memory filesystem:
299 and 300 explicit choices respectively survived 0→1, second preparation/activation was byte-stable,
and remote originals were reread unchanged. Both retain DuckDB and enabled API/WorldEdit; production
update/error reporting remain enabled, Development's disabled settings remain disabled. No original
configuration bytes were persisted by the diagnostic probe.
