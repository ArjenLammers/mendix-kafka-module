# Slice: avro-schema-evolution

<!-- mxcli-brain -->

Requirements for this slice. Anchors point FORWARD, at what the slice
will build — an anchor that does not resolve yet means not built, not
stale. `mxcli brain plan` counts them against the model.

## The on-receive microflow of an Avro consumer can take RecordName (the full name of the writer schema; for a top-level union, the branch of the message) and SchemaId, so a topic with several record types (RecordNameStrategy / TopicRecordNameStrategy) can be handled per type. The record name comes from the writer schema returned for the schema ID, never from a header.

Anchors: `@Kafka.Example_OnReceiveMicroflow_Avro` · id `71918c` · 2026-11-18

## Schema evolution: an Avro configuration can have an optional Get Reader Schema microflow (RecordName, SchemaId, Topic, Consumer) that returns the version a consumer expects. Messages are decoded with the writer schema (from the schema ID) and translated to the reader schema; an incompatible pair (Avro SchemaCompatibility) fails the message with the reason instead of giving wrong data. Cached per writer schema and topic per consumer thread; empty means decode as written; not called for a union writer schema. Format None still needs the writer version from outside the message (e.g. a header).

Anchors: `@Kafka.AvroConfiguration.GetReaderSchemaMicroflow`, `@Kafka.Example_GetReaderSchemaMicroflow` · id `c76810` · 2026-11-18
