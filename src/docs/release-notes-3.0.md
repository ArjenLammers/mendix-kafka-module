# Kafka module 3.0 — release notes

Compared with 2.17. Requires **Mendix 11.12.0 (LTS) or later**.

## Highlights

- **Avro support**: consume and produce Avro messages as JSON, with the Confluent wire format, schemas resolved by a
  microflow (e.g. from your schema registry), and schema evolution.
- **Failure handling per consumer**: a failed message is logged and skipped, or retried with back-off without blocking
  other partitions.
- **Security**: patched libraries; the module's own libraries have no known vulnerabilities (OSV, 18 November 2026).

## Upgrading from 2.17

1. Upgrade your app to Mendix 11.12.0 or later, then import the module.
2. **`SendSynchronous` and `SendAsynchronous` have a new parameter `schemaId`.** Studio Pro reports existing calls as
   out of date: open each call and set `schemaId` to `empty`. Nothing else changes for non-Avro producers.
3. Consumers get the new setting **When a message fails** with default *Log the failure and continue with the next
   message*, as before. Choose *Retry the message until it succeeds* where a message must not be skipped.
4. Failures and retries are now written to the `Kafka` log node; make sure it is visible in your log configuration.

## New

### Avro

- **Avro configuration** per server (Server page, tab *Avro*): name, schema ID format and Get Avro Schema microflow.
  Consumers and producers refer to one; saving sets the byte-array (de)serializer.
- **Schema ID format**: *Confluent* (byte 0 + 4-byte schema ID, as Confluent-compatible serializers write; Confluent
  Platform/Cloud, Redpanda, Karapace, Apicurio in compatibility mode) or *None* (plain Avro data).
- **Get Avro Schema microflow** returns the schema; it can take `SchemaId`, `Topic`, `Key`, any header, and
  `Partition`, `Offset`, `Consumer` (receiving) or `Producer` (sending). Its result is cached per schema ID until the
  consumers and producers restart. Without a schema ID it runs per message and can choose by header.
- **Receiving**: the on-receive microflow gets the message as JSON in `Value` (String), and can take `RecordName`
  (the record type, e.g. `shop.Order`) and `SchemaId`, so a topic with several record types can be handled per type.
  Use import mappings on the JSON. Optional strings arrive unwrapped and decimals as numbers.
- **Sending**: pass JSON to `SendSynchronous` / `SendAsynchronous` with an Avro producer, and a `schemaId` (required
  with format Confluent). Missing fields get their schema defaults. Use export mappings to build the JSON.
- **Schema evolution**: an optional **Get Reader Schema microflow** returns the version of a record your app expects.
  Every message is translated from the version it was written with: fields the producer added are dropped, removed
  fields get the reader's default, aliases match renamed fields. A version that cannot be translated fails with the
  reason instead of giving wrong data.
- The **Explorer** decodes Avro messages the same way.
- Examples in `_USE_ME`: `Example_GetAvroSchemaMicroflow`, `Example_GetReaderSchemaMicroflow`,
  `Example_OnReceiveMicroflow_Avro`.
- Documentation: `docs/avro-messages.html` (how it works, what can be mapped type by type, limitations, tested
  examples, including an end-to-end order through export mapping, Kafka and import mapping).

### Failure handling

- New consumer setting **When a message fails**: *Log the failure and continue with the next message* (default), or
  *Retry the message until it succeeds*, which processes the message again until it succeeds, with a delay that starts at *Retry delay* and doubles up to
  *Maximum retry delay*. Only the failing partition waits, so its order is kept; other partitions keep flowing and the
  consumer stays in its group.
- A message fails when its value cannot be decoded, the Get Avro Schema microflow fails, or the on-receive microflow
  fails. The first failure is logged at ERROR with the stack trace, retries at WARNING, recovery at INFO, on the node
  that processes the partition. Nothing is written to the database on failure, so many nodes and threads do not
  contend for the consumer row.

## Changed

- Upgraded to Mendix 11.12.0 (LTS). The consumer page's content-type drop-down is now a combo box (React client).
- Libraries: Jackson 2.21.7 → 2.22.3, commons-io 2.17.0 → 2.20.0, slf4j-api 2.0.17 → 2.0.18, snappy-java 1.1.10.7 →
  1.1.10.9 (GHSA-gwq9-9ffj-mvrv, GHSA-q233-g92q-qxp6, GHSA-xqrx-qh46-34m7). New: Apache Avro 1.12.2 (with
  commons-compress 1.28.0 and commons-codec 1.19.0). Unchanged: kafka-clients / kafka-streams 4.4.0, lz4-java 1.11.4.

## Fixed

- A producer's before-commit microflow was never registered; an Avro producer now gets the byte-array serializer when
  saved.

## Known limitations

- **Consumer rebalances**: with commit control *Consumer*, messages processed since the last commit are processed
  again by the consumer that takes over a partition. Expect this when nodes start or stop, and every time a Kafka
  setting is saved (it restarts all consumers on all nodes). Make on-receive microflows idempotent.
- **Avro**: with format *None* the message carries no version, so schema evolution needs the version from elsewhere
  (e.g. a header); a top-level union schema is decoded as written; dates and times arrive as numbers; bytes/fixed and
  maps with varying keys cannot be mapped; Avro-encoded keys are not supported; only the Confluent 4-byte schema ID
  format is recognised; schema changes take effect after a restart. See `docs/avro-messages.html`, section 4.
