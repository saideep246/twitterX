# Trending hashtags: design

Status: implemented in `hashtag/` (`TrendScorer`, `HashtagRepository.findTrendRows`, `HashtagService.trending`). The API is unchanged.

## 1. What the previous version did, and what was wrong with it

```
expected = baselinePosts * windowHours / baselineHours
score    = (recentAuthors + newerHalfAuthors) / (1 + expected)
```

candidates = tags with at least `minAuthors` recent authors, **ordered by recent authors, top 200**, then scored in Java.

| # | Finding | Evidence |
|---|---|---|
| H | **Candidates were chosen before scoring, by raw author count.** A real spike with 3 authors never reached the scorer when 200 tags had more authors (all perfectly normal). | A test with 205 always-busy tags and one new tag with 3 authors: the new tag was missing from the result. This was run against the old code and failed, then became the regression test `aRealSpikeIsNotCrowdedOutByHundredsOfBusyTagsThatAreSimplyNormal`. |
| A | Authors in the numerator, posts in the denominator, a unit-less `+1`. The ratio mixes units, and a tag with no history scored simply `2 x authors`. | Scenario table below. |
| B | New tags: expected = 0 gave `2 x authors` with no regard for how little evidence 2 or 3 authors is. | Old score for "brand new, tiny" (2 authors) was 4.0, above an established tag that doubled. |
| E | "Momentum" was `+ newerHalfAuthors`: it double counted the newer authors but never saw a decline relative to the older half. | A tag whose crowd was fading scored the same as a steady one with the same newer half. |
| G | Distinct authors (min 2) was the only defence. Ten just-created accounts on a new tag scored 24, second of ten scenarios. | Scenario 10. |
| - | The query used four `count(distinct ...)`, which sort. | 645-677 ms on 400k posts, 12.5 MB of temp files. |
| - | (Found while testing) `post_hashtags` had no index leading with `post_id`. | See section 5. |

Checked and found **correct**: the time boundaries in the old query (recent `>= recentSince`, baseline `< recentSince` and `>= since`, newer half `>= halfSince`), the exclusion of deleted posts, protected and inactive authors, and that a post with a tag twice counts once.

## 2. The new score

Notation for a tag and a window of `W` hours, with `k = max(1, floor(168 / W))` baseline slices of `W` hours each right before the window.

* `A`  - effective distinct authors in the window
* `P`  - effective posts in the window
* `An`, `Ao` - effective distinct authors in the newer / older half of the window (an author active in both counts in both)
* `Ea` - the average, over the `k` slices, of the effective distinct authors **of that slice**
* `E`  - the average, over the `k` slices, of the effective posts of that slice

*Effective* means: an account's posts count at most `maxPostsPerAuthor` (3) per window-length slice, and each account is multiplied by its age weight `w = clamp(accountAge / matureAccountDays, newAccountWeight, 1)` (3 days, 0.25).

```
lift(o, e, s) = ln((o + s) / (e + s)) - z * sqrt(1/(o + s) + 1/(e + s))        z = 1

participation = lift(A,  Ea, smoothing)      smoothing = 3
volume        = lift(P,  E,  smoothing)
momentum      = lift(An, Ao, 1)

score = participation + 0.5 * volume + 0.5 * momentum
```

Why this shape:

* **`ln` of a ratio with itself.** Every ratio compares a quantity with the same quantity's normal level (authors with authors, posts with posts), so the units agree, matching activity gives 0, double gives +0.69 and half gives -0.69.
* **`+ s` (smoothing) on both sides.** A prior: a tag is treated as if it normally had `s` extra posts/authors per window. It keeps the ratio finite when the history is empty (`e = 0`) and stops 1 against 0 from looking infinite. Raising `s` demands more evidence; lowering it lets small tags rank (a small site may want 1-2).
* **`- z * sqrt(...)`** is about one standard error of a log ratio of two counts (each contributes about `1/count`). Subtracting it ranks by a *lower bound* of the growth: 3 posts against nothing is weaker evidence than 300 against nothing, although both are "infinitely" above expectation. Large counts barely move; tiny counts are pulled below zero.
* **Participation leads** because distinct people are the hardest signal to fake. **Volume and momentum each count half**: they are supporting evidence, correlated with participation, and would double count it at full weight. These two weights are fixed constants, not settings; they only say "participation matters twice as much as each of the other two".
* **Negative momentum** (newer half smaller than the older) gives a negative term, so a fading tag falls below one that is still growing. **Small differences** between the halves are not mistaken for acceleration: 5 against 4 gives a negative lower bound.
* **Baseline distinct authors are per slice.** A distinct count over the whole week would be larger than any single window (an author active once a day appears once in the week but also once in each slice), so it must not be compared with a window's authors. `Ea` is exactly the average number of different people in a window of this length.
* **Brand-new tags** compare with `e = 0`; with enough *different* people the score is positive (18 posts from 12 authors: participation 0.98, volume 1.33). **Popular tags** need to exceed their own normal level, so a tag that is always busy scores about zero or below.
* **Sparse history** (one post last week) differs from none by under 0.02 of score: the smoothing makes both look the same.
* **Eligibility** is unchanged: at least `minAuthors` raw distinct authors. There is deliberately no "score must be positive" filter, so a quiet site still shows its small tags, ranked.
* **Complexity.** Scoring is O(1) per tag, ranking O(c log c) for c <= 500 candidates. Counting is one pass over the posts of the last `(k + 1) * W` hours (about 8 days).
* **Tie-breaking** is unchanged and deterministic: score (compared at 1e-9 so floating point noise cannot decide), then recent authors, recent posts, name.

### Candidate selection

SQL keeps the tags with at least `minAuthors` recent authors, orders them by **participation lift** (the dominant term, computed in SQL from the same figures) and returns the top 500 (was: top 200 by raw author count). A tag that scores high through volume or momentum alone but sits outside the top 500 by participation lift cannot be seen; with an eligibility rule of 2 authors that needs more than 500 tags that are rising faster in participation, so it is not a practical concern, but it is not a mathematical guarantee either.

### Manipulation resistance: what is and is not covered

| Case | Handling |
|---|---|
| One account posting repeatedly | At most 3 posts per window and per slice count; distinct authors count once. |
| Two accounts | Still pass `minAuthors = 2` (unchanged), but with so little evidence the lower bound keeps them near the bottom. Raise `TRENDING_MIN_AUTHORS` on a bigger site. |
| A burst of just-created accounts | Weighted down to 25% (linear to full weight at 3 days). Scenario 10: second place before, fifth after, and below real participation of similar size. |
| Accounts older than 3 days | Not distinguished. A patient attacker who ages accounts is not stopped. |
| Duplicate / near-duplicate text from many accounts | **Not penalised.** Legitimate events produce the same texts ("RIP ..."), and the data cannot tell them apart. Exact repeats by one account are covered by the per-author limit. |
| A sudden burst | Not assumed malicious. A legitimate viral event takes first place (scenario 8). |
| Moderation signals | Suspended / deactivated / protected accounts and deleted posts are already excluded. Reports are deliberately not read: they are unreviewed accusations, and coupling trending to the report tables would let anyone suppress a tag by filing reports. |
| Other account-quality data | None exists, so none is invented. Account age (`users.created_at`) is the only signal used. |

### Seasonality

The baseline is the plain average of the previous `k` windows. A tag with a daily peak (say 20:00) will look like a spike every day at that hour, by about peak / daily mean. A same-hour-of-day baseline was evaluated and not adopted: with 7 sample days per slice it is noisy for sparse tags and needs a blend weight that nothing in the data justifies. The slice structure would let it be added later (compare with slices at offsets of 24 h).

## 3. Old and new on ten scenarios (6 h window, 28 baseline slices; `TrendScenariosTest` asserts all of these)

| Scenario | Old | rank | New | rank |
|---|---:|---:|---:|---:|
| 1 popular, stable (36 authors, normal 35) | 0.95 | 9 | -0.38 | 8 |
| 2 small, rapid growth (10 authors, normal 1.2) | 7.60 | 6 | 1.13 | 4 |
| 3 brand new, 12 authors / 18 posts | 20.00 | 3 | 1.58 | 2 |
| 4 brand new, 2 authors / 3 posts | 4.00 | 7 | -0.25 | 7 |
| 5 one account repeating (60 posts, 2 authors) | 4.00 | 8 | -0.39 | 9 |
| 6 many independent authors (28, normal 4.5) | 7.67 | 5 | 1.47 | 3 |
| 7 declining (11 authors, 3 in the newer half) | 0.67 | 10 | -1.39 | 10 |
| 8 legitimate viral event (250 authors, normal 2.5) | 117.50 | 1 | 5.77 | 1 |
| 9 sparse history (7 authors, 1 post last week) | 11.59 | 4 | 0.82 | 6 |
| 10 twelve accounts one day old, 3-6 posts each | 24.00 | 2 | 0.90 | 5 |

What changed, and what did not: the viral event and the declining tag are first and last in both. The big difference is **who sits second**: the old score put the burst of new accounts there and put the 28-author organic tag fifth; the new one puts the 12 real authors of a new tag and the 28-author tag ahead of it. That is a judgement, not a proof of "better": the old order is defensible if one believes any new tag with many authors should win, and the burst in scenario 10 is still above the 7 established authors of scenario 9 (0.90 vs 0.82), because 12 accounts posting 3 times each is a lot of volume even at a quarter weight.

Possible undesirable outcomes of the new score: genuinely new people (a flood of real newcomers during a viral event) are discounted for 3 days; a brand-new tag with 12 authors can outrank an established tag with 40 authors that grew 3 times (it depends on `smoothing`); the score values are only meaningful relative to each other.

## 4. Performance (measured, PostgreSQL 18.4 on a laptop, single connection, data in cache; not a production number)

Synthetic data: 20,000 users, 8,000 tags with a skewed popularity, posts spread over 60 days, 6 h window.

| Posts in table | Old query | New query |
|---:|---:|---:|
| 400,000 (all within 8 days) | 645-677 ms (sorts 12.5 MB on disk) | 329-346 ms (hash aggregate spills 15.5 MB at the default `work_mem` of 4 MB) |
| 1,600,000 (8 of 60 days) | 329-362 ms | 221-285 ms |

Each figure is the range of 3-4 runs of the final query with `EXPLAIN (ANALYZE)`. Both read the posts of the last 8 days through `idx_posts_created_at` and aggregate about 300k post-tag rows; the new one replaces four sorting `count(distinct)` with hash aggregates and one pass. This is a cost per request and it grows linearly with the posts of the last 8 days, which is why the result is cached in Redis (next section).

### The Redis cache (`TrendingCache`)

* **What is cached:** the top 50 for one window length, as JSON under `trending:v1:<hours>` (at most 168 keys, because the window is clamped to 1-168 hours), with a TTL of `TRENDING_CACHE_TTL` (30 s). A request for fewer than 50 returns a prefix of it, so different `limit` values share one entry. The answer may be up to one TTL old; nothing invalidates it, because it is a ranking that is a little stale anyway.
* **Stampede:** on a miss the caller that wins `SET trending:lock:v1:<hours> NX EX 15` computes and stores; the others poll the key for up to 0.5 s and compute themselves only if nothing arrives. Eight concurrent callers on a real Redis ran the query once (tested).
* **Redis is an optimisation, never a dependency:** on any Redis error the list is computed directly and Redis is skipped for 30 s (as the rate limiter does). The first request after the outage starts waits for the Redis connect timeout (2 s by default); the next ones do not (measured: 2.02 s, then 8 ms and 7 ms). A damaged entry is dropped and recomputed. An error while computing reaches the caller and releases the lock.
* **Turned off** with a TTL of `0`; the test profile does that so tests see fresh data.
* **Not done on purpose:** counters kept up to date in Redis as posts arrive. They would remove the scan of 8 days of posts altogether, but they must be kept consistent with edits, deletions, suspensions, deactivations and the time-dependent account-age weights, and Redis can lose data, so the ranking could drift from the exact SQL result; nothing measured so far justifies it.

### Kafka: refreshing the cache when something changes (optional, `KAFKA_ENABLED`)

The TTL alone means a new tag can take up to 30 s to show. With Kafka on, post and account changes are published after commit and a consumer recomputes the cached lists within a couple of seconds.

```
request -> DB transaction commits -> PostEvent (Spring event, AFTER_COMMIT) -> background thread -> Kafka topic post-events
 Kafka -> consumer group "trending-refresh" -> TrendingRefresher (coalesces: <= 1 refresh / 5 s) -> recompute the cached windows -> Redis
```

* **Why not the counters.** The event stream could also feed per-tag counters, but then every case that changes what counts (edit, delete, suspension, protect, account age passing 3 days) must be replayed exactly, and the result would drift from the SQL. Here an event only means "recompute", so every change (including ones it does not describe) is picked up and the ranking stays exact.
* **What triggers an event:** a post with hashtags created, deleted (by its author or an admin), or its hashtags changed; an account deactivated / reactivated / suspended / unsuspended / removed / protected / unprotected. Not: posts without hashtags, edits that keep the tags, likes, replies.
* **Consistency:** the event is sent after the transaction commits, so the refresh reads the database after the change. A change that lands during a refresh schedules another. Two races remain and are bounded by the TTL: a cache *miss* that started computing before a change can store its older result after the refresh; and a lost event (queue full, Kafka down) is only noticed at the next event or TTL expiry.
* **Delivery is best effort** (no outbox): the events carry nothing that must not be lost. The producer never blocks a request (background thread, bounded queue of 1000, `max.block.ms` 2 s, 30 s cooldown after a failure). The consumer starts at the end of the topic: an old event is worth nothing.
* **Cost:** at most one recompute per cached window per 5 s per instance while posts are being made (about 3 windows x 0.2-0.35 s of query time at the measured size), instead of one per window per TTL. On a very busy site raise `TRENDING_REFRESH_INTERVAL`.
* **Verified** with an in-JVM broker in the tests and by hand against the `docker-compose.yml` broker: a post through the API changed the Redis entry about 2 s later and restarted its TTL; posting stayed at 10-33 ms with the broker stopped, and refreshing resumed by itself when it returned.

### The `post_hashtags (post_id, hashtag_id)` index (migration `V14`)

The table's primary key starts with `hashtag_id`, so nothing could look up the tags of a post. With fresh statistics the planner avoids needing one (hash join), which is why it is invisible in the benchmark above. Without them (right after a bulk load, or when autovacuum is behind) it chose a nested loop doing one full-index scan per post:

| | No usable statistics, 91k posts |
|---|---:|
| Before the index | **70.8 s** (33 million buffer hits) |
| With the index | **92 ms** |

The same lookup is what editing a post (`syncHashtags`) and deleting an account do: 13-394 ms before (1.4M links), **0.05 ms** after. The index is 43 MB at 1.4M links. On a very large table create it first with `CREATE INDEX CONCURRENTLY IF NOT EXISTS`; the migration is then a no-op.

## 5. Testing

* `TrendScorerTest` - the formula (zero history, tiny counts, extreme bursts, below and above expectation, negative momentum), ranking, ties, limits, empty input, input-order independence.
* `TrendScenariosTest` - the ten scenarios with exact scores for the old and new formulas and the rankings.
* `TrendingQueryTest` - PostgreSQL, fixed clock and explicit timestamps: posts exactly on every boundary, the newer half, baseline slices (including the first and last second), distinct authors across slices, the per-author limit, account-age weights, exclusions, the author threshold, window lengths and clamps, result limits, empty data, candidate crowding (505 tags), and a **randomised comparison of every aggregate against an independent Java implementation of the same rules** (1,200 posts, many exactly on boundaries).
* `TrendingPropertiesTest` - validation and defaults.

## 6. Known limitations

* The account-age discount is the only manipulation signal; aged or purchased accounts are not detected.
* No seasonality, no near-duplicate detection (see above).
* Candidate screening is by participation lift, not the full score.
* Kafka (when on) makes the list fresher, not cheaper: every refresh is the same query.
* Cost of a cache miss is linear in the posts of the last 8 days; the cache removes it for all but one request per window length per TTL, and a cold start or an outage brings it back.
* `smoothing`, the 0.5 weights and `z = 1` are judgement calls; they were chosen on the scenarios above, not fitted to real traffic.
