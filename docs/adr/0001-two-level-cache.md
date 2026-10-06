# 1. Two-level cache (Caffeine + Redis) with Pub/Sub invalidation

- Status: accepted
- Date: 2026-10-06

## Context

Product pages are read far more often than products change. Redis alone still costs a
network round trip and deserialization per read; a local cache alone serves stale data
on every instance that did not see the change.

## Decision

[`TwoLevelCache`](../../src/main/java/io/github/alef2509/catalog/cache/TwoLevelCache.java)
implements Spring's `Cache`, so the service just uses `@Cacheable(sync = true)`:

- **L1**: Caffeine in each instance, short TTL (30 s), bounded size.
- **L2**: Redis, shared, longer TTL (10 min).
- Reads: L1 → L2 (filling L1) → database (filling both). `sync = true` makes concurrent
  misses in one instance wait for a single load.
- Writes and evictions go to both levels and are **broadcast on a Redis Pub/Sub channel**;
  other instances drop their L1 copy and reload from L2.
- Values are serialized as JSON of a known type (`ProductView`), without embedded class
  names, so data read back from Redis is never deserialized polymorphically.

## Consequences

- Most reads never leave the JVM; Redis absorbs the rest; the database sees one load per key.
- Pub/Sub is fire-and-forget: an instance disconnected during an invalidation keeps a
  stale L1 entry **at most until its L1 TTL**. That bound is why L1 stays short.
  Where staleness is unacceptable, use Redis only, or client-side caching with Redis
  tracking (`CLIENT TRACKING`), which the server guarantees.
- Memory per instance grows with L1 size; it is capped with `maximumSize`.
