# Slice: consumer-robustness

<!-- mxcli-brain -->

Requirements for this slice. Anchors point FORWARD, at what the slice
will build — an anchor that does not resolve yet means not built, not
stale. `mxcli brain plan` counts them against the model.

## Handle consumer rebalances (deferred)

Register a `ConsumerRebalanceListener` in `kafka.impl.KafkaConsumerRunner`:
- on revoke: commit the offsets processed so far and drop the retry state of the revoked partitions;
- on assign: pick up the retry state of the partition.

A `commitSync` that fails because of a rebalance must not land in the generic catch, which logs
"uncatched exception ... consumer less" and sleeps 30 s. Today, with commit control CONSUMER, records
processed since the last commit are processed again by the new owner.

Pre-existing, but a cluster (more nodes, or ConsumersPerInstance > 1) means more rebalances, and every
saved Kafka setting restarts all consumers on all nodes. Java only, so there is no model anchor.

Anchors: none · id `748ea9` · 2026-11-03
