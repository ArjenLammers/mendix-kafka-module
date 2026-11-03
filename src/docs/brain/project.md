# Project

<!-- mxcli-brain -->

Decisions that are not about one module. This file is loaded every
session, so it carries the tightest cap — see `mxcli brain show`.

## Design every change for a cluster

The module runs in a Mendix cluster: many app instances, each with `ConsumersPerInstance` threads in the
same consumer group per topic. Every change is checked for side effects across nodes and threads before it
is called done:
- no database rows written by several nodes, and no database reads/writes per message;
- runtime code never commits Kafka config entities with events: `OnCh_ConfigChange` updates
  `ConfigChanges`, and every node's `WatchObjectForChange` then restarts all consumers;
- in-memory and static state is per node, and per-partition state is lost on a rebalance;
- logs are spread over the nodes.

Say what was and was not tested: the local tests run one node with one thread.
Decided 2026-11-03, after the failure-handling status on `Kafka.Consumer` turned out not to be
cluster-safe (it was moved to the log).

Anchors: none · id `90162e` · 2026-11-03
