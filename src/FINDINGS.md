# Findings

## 2026-10-19 — Kafka client 4.3.1 → 4.4.0

- **`mx sync-java-dependencies` and every mxbuild/`run --local` build fail on this workspace.**
  The repo is a Windows drive (`D:\`) mounted over drvfs/9p without the `metadata` option, so
  Gradle's `chmod` fails ("Could not set file mode 644/777"). Dependencies resolve fine; only the
  copy into `vendorlib/` / `deployment/` fails. Worked around by running the sync and the app on a
  copy of the project under `/tmp` and copying the resulting `vendorlib/` back. Real fix: mount the
  workspace with `metadata` (or clone into a Linux-native volume).
- **`kafka-streams` 4.4.0 requires `jackson-databind` 2.21.7**; the module pinned 2.21.4, which would
  leave databind and core on different patch levels. Both Jackson pins moved to 2.21.7.
- **`alter module … set jar dependency … version` is not idempotent in mxcli**: a second exec of
  `mdlsource/kafka-dependencies.mdl` reports "Updated" again instead of unchanged.
- **Local end-to-end test setup** (verified: both tests in `tests/kafka.test.mdl` pass):
  - `dockert` broker: a fresh `dockert_kafka-data` volume is root-owned and the broker exits with
    `AccessDeniedException`; `chown` it to uid 1000 once.
  - The snapshot's producers use the server with *runtime certificates*. Pass
    `CACertificates=dockert/secrets/ca.cer` (not `ca.crt` — that is a different CA, `CN=CA`, and
    fails PKIX validation), `ClientCertificates=dockert/secrets/kafka01.p12`,
    `ClientCertificatePasswords=kafka01` via `mxcli run --local --runtime-setting`, then
    `mxcli test tests/ --attach`.
  - `run --local`/`test --local` with `--db-type hsqldb` use their own databases (`kafkamodule`,
    `kafkamodule_test`), not the snapshot's `default`; seed them by copying `default.script`.
  - `Tests.TEST_*` microflows use UnitTesting asserts, which only fail inside a UnitTesting run;
    calling them directly always "passes". `tests/kafka.test.mdl` re-states them with `@expect`.
- **Snapshot data**: two enabled `Kafka.Consumer` rows have no name and no server, so
  `StartConsumer` throws a NullPointerException for each at startup. The snapshot also contains dozens of
  leaked `tmp*.jks` files from `KafkaPropertiesFactory.setEntitySSLParameters`, which never deletes
  its temp keystores.

## 2026-10-20 — Release 2.17

- **The module version is the name of an empty folder** `__Version x.y` in the Kafka module
  (`mx show-module-version` reports none). MDL has no folder rename or empty-folder create;
  `mdlsource/release-2.17.mdl` moves a page in and out to create the new folder, then drops the old one.
- **`mx create-module-package` does not reproduce the Studio Pro export**: it omits the module's
  managed-dependency jars (`vendorlib/`) and adds other modules' `userlib/` jars, widgets and
  `resources/logo.png`. `DIST/Kafka-2.17.mpk` was assembled to the 2.16 layout: the exported
  `project.mpr` and `javasource/kafka`, plus the Kafka module's own resolved jars (unchanged
  `slf4j-api-1.7.36`, `snappy-java`, `jackson-annotations` taken from 2.16).
- Verified by `mx module-import` into a blank 10.21.0 app: the same check errors as 2.16 imported the
  same way (only the expected missing Encryption/MxModelReflection/LibraryLogging references).

## 2026-10-22 — 2.17 rebuilt: lz4-java pinned to 1.11.4

- The Marketplace scan rejected the first 2.17 build: `at.yawk.lz4:lz4-java` <= 1.11.3 is affected by
  GHSA-mcr4-qmvw-px4g (CVE-2026-106451, native library extracted to a shared temp dir). OSV also lists
  GHSA-343h-94h5-c4wr and GHSA-gm45-99xc-r7wv for 1.11.2. 2.16 shipped 1.10.2, equally affected; the
  advisory postdates that release.
- `kafka-clients` 4.4.0 (still the latest) requires 1.11.2, so the Kafka module now declares
  `at.yawk.lz4:lz4-java` 1.11.4 explicitly; Gradle resolves to it. Verified: native LZ4 JNI loads and
  round-trips, both `tests/kafka.test.mdl` tests pass with only 1.11.4 on the classpath, OSV reports no
  known vulnerabilities for any of the nine bundled jars, and the rebuilt `DIST/Kafka-2.17.mpk` imports
  into a blank 10.21 app with the same check errors as 2.16.

## 2026-10-22 — upgraded 10.21.0 → 11.12.0 (LTS) for 3.0

- Converted with `mx convert --in-place <dir>` from the cached 11.12.0 toolchain, after a dry run on a
  copy in /tmp. `_SchemaHash` after conversion equals a blank 11.12.0 project's:
  `{SHA256}ex8TFkjI5tikVWCC05OxODFVHlheQtT9XpJxPAwVGY0=`. The project stays single-file MPR v1.
  The converter also refreshed several bundled widget `.mpk`s.
- One new error, CE0582: the legacy `Forms$DropDown` for `Consumer.ContentType` on
  `Kafka.Consumer_NewEdit` is not supported by the React client (the only client in 11). Replaced
  with a combobox via `mdlsource/upgrade-11.12.mdl` (same label and on-change microflow,
  `clearable: false`). The old widget's `$value != empty` validation has no combobox equivalent.
- mxcli trap: `ALTER PAGE … SET (clearable: false)` on a combobox stores PrimitiveValue `"no"`; setting it
  inside the widget definition (CREATE/REPLACE) stores `"false"`. Found by comparing `mxcli bson dump`.
- `mx check` on 11.12: 0 errors; `docker check` (with and without `--no-update-widgets`): passed.
- `mxcli test --local` cannot build on the drvfs (`D:\`) mount: 11.12's Gradle `copyLibs` fails with
  "Could not set file mode 777". On a copy in /tmp it builds and boots; both tests then fail with a null
  `Producer`, because they need the SSL broker from `dockert/compose.yaml` and seeded Producer records.
  Since run against the broker; see the entry below.

## 2026-10-22 — storage converted MPR v1 → v2 (mprcontents/)

- No headless tool does this. `mx convert` keeps the format. The package route
  (`mx create-project-package` + `mx create-project <mpk>`, which defaults to v2) is NOT lossless:
  it gives the project root unit a new ID and strips the Sprintr feedback widget from
  `Atlas_Core.FeedbackWidget` and two `Header` layouts.
- Converted instead by writing each `Unit.Contents` blob to `mprcontents/<id[0:2]>/<id[2:4]>/<id>.mxunit`
  (UnitID is a little-endian GUID), dropping the `Contents` column, `ContentsHash` = base64(sha256(file)),
  `_MetaData._FormatVersion` = 2 and a `_Transaction` table, all copied from the layout of a blank 11.12 v2 project.
  One v1 unit had a stale `ContentsHash`; it was recomputed.
- Verified: `mx dump-mpr` output byte-identical before/after (24.6 MB), `mx check` 0 errors,
  `mxcli fix hashes` reports the index matches every unit file, lint unchanged (0 errors, 22 warnings),
  `docker check --no-update-widgets` passed (with TMPDIR off /tmp, which has only ~360 MB free).

## 2026-10-22 — tests pass on 11.12.0 against the SSL broker

- Both `tests/kafka.test.mdl` tests pass on the converted v2 / 11.12.0 project (string round trip with header,
  binary FileDocument round trip with matching size, ~5 s each).
- Setup: `docker compose up -d` in `dockert/`. The project was copied to a Linux-native directory
  (`~/.cache/kafka-test`, without `dockert/kafka-data`), because drvfs blocks Gradle's chmod and `/tmp` has only
  ~360 MB free. `data-snapshot.zip` was extracted into `deployment/`, with `hsqldb/default/default.*` copied to
  `hsqldb/kafkamodule/kafkamodule.*`. Then
  `mxcli run --local --detach --test-endpoint --db-type hsqldb` with the three certificate runtime settings
  from the 4.4.0 entry, then `mxcli test tests/ --attach`.
- The 10.21 snapshot database needs two boots on 11.12: the first runs the schema sync and then stops with
  "The database has to be updated"; the second starts cleanly.
- `--attach` needs the app started with `--test-endpoint`; without it the run fails with "no test endpoint is being hosted".
- `TMPDIR` must exist: a container restart removed `~/.cache/mxcli-tmp`, and mxbuild then crashed in
  `SystemModulePlugin.LoadSystemModule` with DirectoryNotFoundException.

## 2026-10-23 — merged PR #8 (Avro support) into 11.12.0 / MPR v2

- The PR branched from 17b5302 (10.21, MPR v1), so git cannot merge the model. Merged the model with
  `mx merge BASE MINE THEIRS` after `mx convert` of the base and PR `.mpr` to 11.12 (in a scratch area).
  It auto-resolved everything (including `Consumer_NewEdit`, where both sides edited) except
  the Kafka module settings (Java dependencies). That was resolved by hand: our list plus `org.apache.avro:avro` 1.12.2. Then cleared
  the `ContentsConflicts` flag in the `.mpr` Unit table. `mx merge` keeps MPR v2 and leaves a
  `modeler-merge-marker` file next to the result (not copied).
- The PR's combobox triggered CE0463 after the merge; `mxcli fix widgets` fixed it and kept v2 (784 units).
- avro 1.12.2 pulls Jackson 2.22.1, and Gradle resolves the highest version, so the Jackson pins moved 2.21.7 → 2.22.1 to match
  what ships. It also brings commons-compress 1.28.0 and commons-codec 1.19.0, and lifts commons-io to 2.20.0 and slf4j-api to
  2.0.18. `sync-java-deps` then still reports commons-io 2.17.0 / slf4j-api 2.0.9 as "missing": those are other modules'
  declarations resolved upward, not real gaps.
- `mdlsource/kafka-dependencies.mdl`: `ADD JAR DEPENDENCY` fails once the dependency exists, so the script was not
  re-runnable after the lz4 pin. It now uses SET only; lz4 and avro were added once by hand.
- Verified on the Linux copy against the SSL broker: build OK, both `tests/kafka.test.mdl` tests pass, and an ad-hoc
  `Kafka.TestEnDeCoding` check gives the correct JSON (unwrapped nullable string, decimal, enum, defaults
  filled; a second schema passed with a null hash is honoured). No Avro round trip through Kafka has been tested yet.
- Review notes on the PR code (not changed): `AvroProcessor.schemaCache` is an unsynchronized static `HashMap`
  used from several consumer threads, and it grows without limit when keyed by schema text. The consumer reads the Confluent schema ID
  but only passes it to the optional GetAvroSchema microflow (there is no registry client). Turning `UseAvro` off does not reset
  the ByteArray (de)serializer that `BCo_Producer`/`BCo_Consumer` set.
