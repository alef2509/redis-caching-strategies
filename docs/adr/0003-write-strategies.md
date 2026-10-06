# 3. Write-through for prices, write-behind for view counters

- Status: accepted
- Date: 2026-10-06

## Context

Two kinds of writes with opposite needs:

- **Prices** must be correct right away, at a low write rate.
- **Product views** arrive at a very high rate, a lost or delayed count is acceptable, and
  incrementing one row per view would serialize on that row's lock.

## Decision

**Write-through (prices).** Update the database, then, *after the commit*
(`TransactionSynchronization.afterCommit`), put the new value in the cache and evict the
affected listing. Writing the cache inside the transaction could publish a price that is
then rolled back.

**Write-behind (views).** [`ViewCounter`](../../src/main/java/io/github/alef2509/catalog/writebehind/ViewCounter.java)
increments a Redis hash (`HINCRBY`). Every 5 s a flush:

1. atomically **renames** the pending hash to a uniquely named batch (Lua: `EXISTS` +
   `RENAME`), so new views go to a fresh hash meanwhile;
2. applies the batch in one transaction that also inserts the batch id into `view_flushes`;
3. deletes the batch from Redis.

If the process dies between 2 and 3, the next run finds the leftover batch, hits the unique
batch id, and only deletes it. **Each view is counted exactly once.**

## Consequences

- One database round trip per flush instead of per view; hot products cause no row-lock contention.
- Views are visible in the database up to one flush interval late (the stats endpoint
  also reports the pending count).
- If Redis loses data before a flush (no persistence, failover), those views are lost.
  Acceptable for counters, not for money.
