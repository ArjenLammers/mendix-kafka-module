# Kafka

<!-- mxcli-brain -->

Decisions anchored to the Kafka module. Loaded when Kafka is in play,
not otherwise.

## The Get Avro Schema microflow's result is cached per consumer thread, keyed by the message's schema ID, until the consumers restart (a config save). So the microflow must return the same schema for the same schema ID and must not choose by key, partition or header. The cache is per thread on purpose: no shared state or DB access in a cluster; cost is one microflow call per schema ID per thread per node. Messages without a schema ID (format None) call the microflow every time. The schema ID format is explicit (Confluent / None) instead of guessed from a leading 0 byte.

Anchors: `@Kafka.Consumer.SchemaIdFormat`, `@Kafka.Consumer.GetAvroSchemaMicroflow` · id `ab5d4a` · 2026-11-12
