# 4. Distributed rate limiting with a token bucket in Lua

- Status: accepted
- Date: 2026-10-06

## Context

The API must protect itself from clients that send bursts, consistently across all
instances, while allowing short bursts.

## Decision

A **token bucket** per client (API key, else IP), stored in a Redis hash and updated by a
single Lua script ([`RateLimiter`](../../src/main/java/io/github/alef2509/catalog/ratelimit/RateLimiter.java)):

- read, refill, take and write in one atomic step, so two instances cannot both take the last token;
- time comes from the **Redis server clock** (`TIME`), so instances with skewed clocks agree;
- the key expires once the bucket would be full again, so idle clients cost nothing.

Responses carry `RateLimit-Limit` and `RateLimit-Remaining`; a rejection is `429` with
`Retry-After` and a problem-details body.

## Consequences

- Allows bursts up to the capacity, then a steady rate; fair per client.
- One Redis round trip per request. If Redis is down, requests fail; a production setup
  would decide between failing open and closed explicitly.
- Fixed and sliding window counters are simpler alternatives, but they allow up to 2×
  the limit around a window boundary (fixed) or cost more memory (sliding log).
