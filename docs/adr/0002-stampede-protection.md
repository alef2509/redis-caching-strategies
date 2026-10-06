# 2. Cache stampede protection for hot keys

- Status: accepted
- Date: 2026-10-06

## Context

A category listing is expensive and requested constantly. When its cache entry expires,
every request in flight misses at the same moment and goes to the database: the
**stampede** (or dog-pile), which can take the database down at peak traffic.
`@Cacheable(sync = true)` only coordinates threads of one JVM.

## Decision

[`StampedeProtectedCache`](../../src/main/java/io/github/alef2509/catalog/cache/StampedeProtectedCache.java) combines three techniques:

1. **Probabilistic early expiration (XFetch)**. Each entry stores how long it took to compute
   (`delta`) and when it logically expires. A read refreshes early when
   `now − delta · β · ln(rand) ≥ expiry`: almost never while the entry is young, increasingly
   often near expiry, sooner for expensive values. One request refreshes the hot key *before*
   it expires, while everyone else keeps hitting the cache.
2. **Single flight across instances**: the refresher must win `SET lock:<key> <token> NX PX 10000`.
   Losers serve the current value, or on a completely cold key poll briefly for the winner's
   result, falling back to computing themselves after 1 s (fail open). The lock is released
   with a compare-and-delete Lua script, so a slow holder never deletes someone else's lock.
3. **TTL jitter (±10 %)**, so keys written together (after a deploy or a flush) do not expire together.

## Consequences

- The integration test sends 100 concurrent requests for a cold key: **one** database query.
- Slightly stale data during a refresh (bounded by the lock TTL), which is fine for a listing.
- This is a simple lock, not Redlock: if Redis fails over and loses the lock, two instances
  may compute at once. That only costs one extra query, and correctness never depended on the lock.
