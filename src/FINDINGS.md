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

## 2026-11-03 — configurable handling of failed messages

- New `Kafka.Consumer.OnFailure` (`Kafka.FailureHandling`: SKIP default, STOP), plus status written by the consumer:
  `LastFailure`, `LastFailureDate`, `StoppedOnFailure`. Administrators can only read the status: a member-listed access rule gave
  the new attributes write access, so `mdlsource/failure-handling.mdl` revokes it.
- A failed message = a value that cannot be decoded, a failing Get Avro Schema microflow (before: silently fell back to the
  default schema), or a failing on-receive microflow. Before this change a decode error escaped the per-record handling and
  dropped the rest of the batch after a 30 s sleep. The per-record work is now `processRecord()`, and every failure goes
  through `handleFailure()`.
- STOP seeks every partition of the batch back to its first unprocessed record and commits that position (both commit
  controls), so a restart begins with the failed record. Restart = `Sub_Restart` (saving any Kafka config, or app restart).
  The status is written with `Core.commitWithoutEvents`, because a commit with events counts as a config change and restarts
  every consumer. OnFailure is read fresh from the database at failure time.
- Tests: two new tests in `tests/kafka.test.mdl`, plus a test hook in `Tests.Kafka_ListenerForTestTopicString` (payload
  `fail-on-purpose` throws). They need `@cleanup none`: by default each test runs in a transaction that is rolled back, so
  `CommunityCommons.EndTransaction` does not commit, and the test's lock on the Consumer row blocks the consumer thread on
  HSQLDB until the test ends (the first attempt showed "did not stop within 30000 ms"). Verified in the runtime log: the same
  offset fails twice, first STOP, then after the restart SKIP, and the next message is processed. All four tests pass.
- Only tested with commit control CONSUMER. SERVER (auto commit) relies on the same seek: close() auto-commits the position
  after the seek.
- With several threads or nodes per consumer, STOP stops only the thread that hit the message. The partition is then reassigned,
  hits the same message and stops there too, while other partitions keep flowing until they fail.
- Generated proxies: only `kafka/proxies/Consumer.java` and the new `FailureHandling.java` were taken from the 11.12 build
  (LF line endings); 11.12's generator reformats every proxy, and Studio Pro regenerates the rest anyway.

## 2026-11-03 — failed messages: Stop replaced by Retry (retry until it succeeds)

- Supersedes the Stop option in the previous entry. `Kafka.FailureHandling` is now SKIP (default) / RETRY. RETRY retries the
  message forever (decided by the user: nothing is lost, and a broken message blocks its partition until the cause is fixed).
  The delay is `RetryDelaySeconds` (default 5), doubled per attempt, capped at `MaxRetryDelaySeconds` (default 300).
  Status: `FailedAttempts`, `NextRetry`, plus `LastFailure`/`LastFailureDate`. `StoppedOnFailure` was dropped (never released).
- The retry does not block the poll loop: the partition is moved back to the failed record and paused, and resumed when the retry is
  due, while the loop keeps polling every 100 ms. So `max.poll.interval.ms` is never exceeded and the consumer keeps its group
  membership. Later records of that partition wait (order kept); other partitions keep flowing. The commit at the end of a batch
  stops at the failed record, because the paused partition's position was moved back to it.
- Retry state lives in memory per runner thread. After a restart or a rebalance the record is retried from the committed offset
  and the attempt count starts again at 1. With several threads or nodes, `FailedAttempts`/`NextRetry` show whichever
  partition failed last.
- First failure is logged at ERROR with the stack trace; later attempts at WARNING with the error chain only, to keep an
  endless retry from flooding the log.
- Retry test hook: payload `fail-until-released` fails until the test commits a `Tests.Message` with Reference
  `release:<reference>`. Verified in the log: attempts 1, 2, 3 with 1 s, 2 s, 2 s delays, then "processed … after 3 failed
  attempt(s)" once released; the message behind it was processed after it. All four tests pass. Lint +1: the second hook throw
  (CONV013, intentional).

## 2026-11-03 — failed messages: status moved from the database to the log

- Removed `Consumer.LastFailure`, `LastFailureDate`, `FailedAttempts`, `NextRetry` and their page fields (never released). The
  failure path no longer reads or writes the database. That removes the cluster problems from the review: last-writer-wins status
  on one shared row, and one read plus one write per failed message under Continue.
- `OnFailure`, `RetryDelaySeconds` and `MaxRetryDelaySeconds` are read once when the runner starts; saving any Kafka setting
  restarts all consumers on all nodes. Consequence for the tests: changing the setting needs `Kafka.Sub_Restart` (+10 s to rejoin),
  and the Retry test restarts again at the end so consumer-string is not left on Retry in memory (a later 'fail-on-purpose'
  would otherwise block partition 1 forever).
- Log lines (Kafka log node, on the instance that owns the partition): first failure ERROR with stack trace; later attempts
  WARNING `attempt n, retrying in x s` with the error chain; recovery INFO `processed … after n failed attempt(s)`.
- Tests assert on processed messages instead of status fields. Continue: the failed message is not stored and the next one is.
  Retry: the next message waits, and both are processed after the release.
- Studio Pro had the project open during this change (`KafkaModule.mpr.lock`, Windows PID). mxcli refused to write, and the change
  was verified in the Linux test copy first. Once the lock was gone, the same scripts were applied to the project; the resulting
  model is unit-for-unit identical to the tested one. Studio Pro left the model untouched; it only regenerated
  `themesource/atlas_core/web/_generated-exclusion-variables.scss` and added `widgets/widgets-appstore-metadata.json` (not committed).

## 2026-11-03 — CVE check of all libraries (OSV), Kafka module patched

- Queried OSV (api.osv.dev, Maven) for all 35 components in `vendorlib/vendorlib-sbom.json`.
- **Regression of mine:** the Jackson pins were raised 2.21.7 → 2.22.1 for avro during the PR #8 merge. 2.22.1 lacks fixes
  that 2.21.7 already had: databind GHSA-cxp5-3px4-pw24 (HIGH), GHSA-wv8q-qhhj-9h54 (HIGH), GHSA-q4xh-88c3-wmh7 (HIGH),
  GHSA-gx83-3vf8-gh7j, GHSA-wjgm-6hv5-3cvf; core GHSA-7hhh-6rmp-j9qf (HIGH), GHSA-p6pp-m3f8-5c89 (HIGH). Now 2.22.3.
  Lesson: when a pin moves to a newer minor line, check the newest patch of that line, not the first one that resolves.
- **snappy-java 1.1.10.7** (transitive from kafka-clients 4.4.0): GHSA-gwq9-9ffj-mvrv (HIGH), GHSA-q233-g92q-qxp6 (HIGH),
  GHSA-xqrx-qh46-34m7, published 2026-10-27, after the 2.17 scan. Pinned 1.1.10.9 in the Kafka module, like lz4.
  Verified: native round trip with jshell (`Snappy.getNativeLibraryVersion()` 1.1.10, compress/uncompress equal).
- After the fix, OSV reports nothing for any library the Kafka module brings. All four tests pass, and the build loads with no class errors.
- **Still affected, not fixed: marketplace modules of the test app, not shipped in the Kafka .mpk.**
  - LibraryLogging: log4j-core 2.25.3 (4 × MODERATE, fixed 2.25.4) and log4j-api 2.25.3 (MODERATE, fixed 2.25.5).
  - CommunityCommons: guava 33.4.7-jre, GHSA-xxph-c9ww-hj94 (HIGH, deserialization, fixed 33.7.2).
  - UnitTesting: httpclient5 5.6.2, GHSA-hjcp-jmpx-g3qm (MODERATE, fixed 5.6.3).
  Fix by updating those modules from the Marketplace, not by editing their dependency lists (an update overwrites them).
- Cluster: dependency bumps are per build, so all nodes get them together. During a rolling deploy, old and new nodes coexist;
  Jackson and snappy patch releases keep the wire formats, so that is harmless.

## 2026-11-12 — Avro: explicit schema ID format, Get Avro Schema result cached per schema ID

- New `Kafka.SchemaIdFormat` (CONFLUENT default / NONE) on `Kafka.Consumer`, on the consumer page under Use AVRO (visible
  when Avro is on). Confluent: a message must start with byte 0 and a 4-byte schema ID, otherwise it fails (IOException
  "does not start with byte 0 and a 4-byte schema ID") and goes through Continue/Retry. None: the whole value is Avro data,
  schema ID -1. Replaces the first-byte guess (⚠2) in `KafkaConsumerRunner` and in the Explorer's `GetMessagesFromOffset`;
  both use `AvroProcessor.unframe`.
- `KafkaConsumerRunner` caches the Get Avro Schema microflow's parsed schema per schema ID, per runner thread (LRU, 100),
  including "no schema" (= default schema). Failures are not cached. A config save restarts consumers and empties it.
  The default schema is parsed once per runner. A consumer with no default schema and no microflow result now fails the
  message with a clear CoreException instead of an NPE.
- `AvroProcessor`'s shared schema cache (producers, Explorer) is now a synchronized LRU (100) instead of an unbounded
  unsynchronized HashMap.
- `Producer.ConfluentSchemaId` default 1 → 0 (new producers send plain Avro, not a probably-wrong ID 1). Existing rows keep
  their value.
- **Bug found and fixed:** `SendSynchronousAvroWithSchema` sent every message twice (the PR #8 branch added a
  KafkaSendHelper send to return RecordMetaData but left the old `ProducerUtils.sendSynchronous` call above it). Seen as
  pairs of equal messages on consecutive offsets of `test-avro`; gone after the fix.
- Tests: three Avro tests in `tests/kafka.test.mdl` on a new topic `test-avro` (added to `dockert/compose.yaml` kafka-init)
  with fixtures `Tests.TestSetup_AvroConsumer`, `Tests.Kafka_ListenerForTestTopicAvro`, `Tests.Kafka_GetAvroSchemaForTest`
  (records each call as `schema-call:<reference>`). Verified: 3 messages with schema ID 42 → 1 microflow call; unframed
  message with format Confluent → skipped, next processed; format None with a value starting with byte 0 (Age 0) decodes
  correctly, microflow called per message with -1. All 7 tests pass. 1 node, 1 thread only.
- mxcli test quirk: an XPath `(A or B) and C` whose terms concatenate strings (`'x:' + $Ref`) matched nothing, while each
  term alone works; build the strings in a `declare` first.
- Not tested: the Explorer (`GetMessagesFromOffset`) path, several threads or nodes.
- Follow-up: the five script-only tests are also UnitTesting tests in the model (`Tests.TEST_FailureContinue`,
  `TEST_FailureRetry`, `TEST_AvroConfluentSchemaCache`, `TEST_AvroConfluentRejectsUnframed`, `TEST_AvroNoneFormat`,
  with helpers `SUB_RestartConsumers`, `SUB_SendString`, `SUB_SendAvro`, `SUB_CountSchemaCalls`; script
  `mdlsource/tests-unit.mdl`). Asserts use StopOnFailure = false, so the clean-up (consumer-string back to Continue)
  always runs. Verified through the UnitTesting remote API (`--constant UnitTesting.Enabled=true
  --constant UnitTesting.RemoteApiEnabled=true --constant UnitTesting.RemoteApiPassword=…`, then POST
  `/unittests/start` and `/unittests/status` with `{"password": …}`): 18 of 18 pass (Tests suite, UnitTesting examples,
  JUnit examples). The project's microflows describe identically to the tested ones in the Linux copy.
- **UnitTesting transactions:** the runner starts a transaction on the test context and the microflow call nests a second
  one, so `CommunityCommons.EndTransaction` only ends the inner one and the outer one is rolled back at the end. Records
  committed that way are never seen by the consumer threads (their own sessions): `TEST_FailureRetry` first failed because
  its release marker never arrived. Fix: `Tests.CommitMessageNow` commits it in a context of its own (the script test
  uses it too). Setting changes still work: `Kafka.Sub_Restart` retrieves the consumers inside the test's transaction, so
  the test must `commit without events` its change (an uncommitted change is not seen by that retrieve; a run without
  the commit ran the Retry test with Continue).
- Lint after the test refactor: 36 warnings (24 before this change), the 12 new ones all CONV013 (default error handling on
  Java calls) in the new test microflows, the same pattern as the existing TEST_ microflows. Report 76/100 (81 before).
- 2026-11-13: the five UnitTesting tests got What/Why documentation and a note (annotation) at each step: setup, what
  is sent, what is waited for, what is expected, and the clean-up. Rebuilt with `tests-unit-rebuild.mdl` (exec refuses to
  splice notes into stored flows). 18 of 18 still pass. Lint counts notes as activities: CONV009 (info) now fires on
  `TEST_FailureRetry` and `TEST_AvroConfluentSchemaCache`; report 75/100. One `docker check` run failed with no
  message and passed on the immediate rerun (0 errors).
- 2026-11-13: `tests/kafka.test.mdl` removed at the user's request: all Kafka tests now live in the model as UnitTesting
  tests (`Tests.TEST_*`), run from the UnitTesting page or its remote API. `mxcli test` has nothing to run any more.
- 2026-11-13: end-to-end Avro test `Tests.TEST_AvroOrderEndToEnd` (scripts `mdlsource/tests-avro-order.mdl`,
  `tests-avro.mdl`, `tests-unit.mdl`). An order schema (`Tests.AvroSchemaOrder`, served by `Kafka_GetAvroSchemaForTest`
  for schema ID 43) with an enum, timestamp-millis, decimals, nullable string/int, a nested record, a nullable named
  record, an array of records and an array of strings. Two orders (full, and minimal with every optional empty) go
  export mapping → Avro (Confluent framing) → Kafka → consumer → JSON → import mapping, then are compared field by
  field. The listener now stores the JSON in `Tests.Message.Body` (unlimited) instead of `Payload`. 19 of 19 pass.
  What it showed:
  - Mendix reserves the enumeration value `NEW` (CE7247): an Avro enum with symbol `NEW` cannot map to a Mendix enum
    one to one (map it to a String, or rename the symbol).
  - An export mapping **group** writes `{}` when there is no object, which Avro rejects for a union
    ("Expected start-union. Got END_OBJECT"). A wrapped optional (`{"shop.Address": {...}}`) needs a wrapper entity
    (`AvroShippingAddress`) so the element is left out when it is empty (`null values LeaveOutElement`).
  - The import mapping creates the wrapper object for `"priority": null` (without a value). Compare optionals by value,
    not by whether the wrapper exists.
  - Value converters for timestamp-millis failed to build (CE0228/CE0230/CE5015): the millis go into a Long attribute
    (`CreatedAtMillis`) and `SUB_EpochMillisToDateTime` / `SUB_DateTimeToEpochMillis` convert them.
  - A JSON structure sample with a raw control character silently creates nothing.
  - `run --local` runtime settings `CACertificates`/`ClientCertificates` need absolute paths; a relative path fails at
    boot with "not a valid PKCS12 certificate" (NoSuchFileException). The mxbuild NullReference on the first boot
    attempt recurred once and passed on the retry.
  - `exec` of a script with `create or modify java action` rewrote `CommitMessageNow.java` without the class Javadoc and
    with CRLF (the model keeps the documentation); restored the committed file.
  - Lint: 40 warnings (36 before): 3 CONV013 and QUAL003 (32 activities) on `TEST_AvroOrderEndToEnd`. Report 73/100.
    `docker check`: 0 errors.
