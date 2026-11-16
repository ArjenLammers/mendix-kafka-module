# Kafka

<!-- mxcli-brain -->

Decisions anchored to the Kafka module. Loaded when Kafka is in play,
not otherwise.

## Avro settings live in Kafka.AvroConfiguration (per server), referred to by Consumer and Producer; an empty reference means no Avro. There is no stored schema: the configuration's Get Avro Schema microflow is the only way a schema is resolved, on both sides, with the same parameters (SchemaId, empty when there is none; Topic, Key, header names; Partition/Offset/Consumer when receiving, Producer when sending). Its result is cached per schema ID: per consumer thread (no shared state between threads or nodes), and per configuration for all sending threads of a node (static map, emptied by StopAll on every restart, which a config save triggers on all nodes). Without a schema ID it runs per message and may choose by header. Format Confluent needs a schema ID per send (SendSynchronous/SendAsynchronous `schemaId`); format None writes no prefix. The send-side microflow runs in a new system context so it never ends the caller's transaction. Breaking change (2026-11-16): the old Consumer/Producer Avro attributes and the two ...AvroWithSchema actions were dropped without migration, at the user's choice.

Anchors: `@Kafka.AvroConfiguration`, `@Kafka.AvroConfiguration.SchemaIdFormat`, `@Kafka.AvroConfiguration.GetAvroSchemaMicroflow` · id `ab5d4a` · 2026-11-16
