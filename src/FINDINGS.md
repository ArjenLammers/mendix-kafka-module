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
