# X clone backend

A minimal X (Twitter) clone REST API built with Spring Boot 4, PostgreSQL, and Cloudflare R2 for images.

Features: JWT auth (access + rotating refresh tokens), profiles, posts with up to 4 images, replies, likes, follows, blocking, a home timeline, and user search.

## Running locally

Requirements: Java 17+ and a local PostgreSQL.

```bash
createdb -U postgres xclone          # once
createdb -U postgres xclone_test     # once, used by the tests
./mvnw spring-boot:run
```

The schema is managed by Flyway (`src/main/resources/db/migration`) and applied on startup; Hibernate only validates it. **Any entity change needs a new `V<n>__*.sql` migration.** Configuration comes from environment variables; see `.env.example`. The defaults connect to `localhost:5432/xclone` as `postgres`/`postgres`. **`JWT_SECRET` (at least 32 bytes) is required**, so export one before running, e.g. `export JWT_SECRET=$(openssl rand -hex 32)`.

### Redis

Redis backs shared state (rate limits, caches, counters) so the API can run as several instances. Locally run
`docker compose up -d redis`; in production set `REDIS_URL` (Upstash: the `rediss://` TLS URL). The app connects lazily.

### Rate limiting

Redis-backed fixed-window limits (`ratelimit/RateLimitRules.java`), answering `429` with `Retry-After`:

| Scope | Limit |
|---|---|
| Login, per IP | 10 / min |
| Register, per IP | 10 / hour |
| Forgot / reset password, per IP | 5 / 10 per hour |
| Other `/auth` calls, per IP | 60 / min |
| Create post / thread, per user | 100 / hour, 20 / hour |
| Send message (REST), per user | 60 / min |
| Media upload URL, per user | 30 / min |
| Follow, per user | 100 / hour |
| Report user / post, per user | 20 / hour |
| Any other write (`POST/PUT/PATCH/DELETE`), per user | 120 / min |

Reads are not limited. If Redis is unreachable the limiter fails open and retries Redis after 30 s. Behind a load balancer set
`FORWARD_HEADERS_STRATEGY=framework` so limits use the real client IP; leave it unset otherwise (the header is spoofable).
**Important:** on a managed host (Render, Fly, Railway, a cloud load balancer) the app sits behind a proxy, so without that setting every user shares the proxy's IP and therefore one login limit. Set it there, and only if clients cannot reach the app except through the proxy.
`RATE_LIMIT_ENABLED=false` turns it off. Messages sent over the WebSocket are not covered yet.

### Real-time (WebSocket / STOMP)

Connect to `ws://<host>/ws` and send the access token in the STOMP `CONNECT` frame header `Authorization: Bearer <token>`
(browsers cannot set headers on the handshake). Subscribe to per-user queues:

| Destination | Payload |
|---|---|
| `/user/queue/notifications` | a new notification, same shape as items from `GET /notifications`. Pushed after the action commits; none for blocked pairs or self-actions |
| `/user/queue/messages` | a direct message received |
| `/user/queue/sent` | ack of a message you sent over WebSocket |
| `/user/queue/errors` | errors from messages you sent over WebSocket |

Send messages to `/app/conversations/{id}/messages`. Pushes are best-effort: a client that is offline or reconnecting misses them, so
on connect (and reconnect) fetch `GET /notifications/unread-count` and the latest page over REST, then increment from pushes.

**Inbox.** `GET /conversations` lists only conversations that have at least one message, most recently active first. Each item carries
`lastMessage` (`id`, `senderId`, `content`, `createdAt`) and `unreadCount` (messages from the other person you have not read). The cursor
is the id of the newest message, so paging is exact even while new messages arrive. `GET /conversations/{id}` has the same two fields (`lastMessage` is
null before the first message); `GET /conversations/unread-count` is the total for a badge.

**Photos in messages.** A message can carry up to 4 photos, with or without text. Upload each one as for a post (*Cloudflare R2 setup*: `POST /media/upload-url`, then PUT the bytes), then send
`{content?, mediaKeys?}` to `POST /conversations/{id}/messages` (or the WebSocket destination). At least one of text or a photo is required; each key must be your own upload that really reached the bucket (`400` otherwise).
Messages carry `mediaUrls` (empty for none) and the inbox's `lastMessage.hasMedia` says the newest message has photos. Editing changes the text only (a photo message may lose its text);
deleting a message removes its photo rows with the text, so the chat shows only the "deleted" placeholder. The image files themselves stay in the bucket.

**Editing and deleting messages.** The sender can change or remove their own messages, with no time limit:

| Method | Path | Notes |
|---|---|---|
| PATCH | `/conversations/{id}/messages/{messageId}` | `{content}` (1 to 2000 characters; may be empty only on a message that has photos). Returns the message with `editedAt` set. Saving the same text changes nothing. `403` if it is not yours, `404` if it is not in that conversation, `409` if it was deleted |
| DELETE | `/conversations/{id}/messages/{messageId}` | Deletes it **for both people**: the text is erased from the database and the row stays as a placeholder (`deleted: true`, `content: ""`) so the chat keeps its order and the inbox keeps working. `204`, safe to repeat |

A deleted message no longer counts as unread (per conversation, in the total, and in the inbox). Both endpoints answer `403` when the pair is blocked or inactive, like sending does.
The other person's open chat is told over `/user/queue/message-updates` (a `MessageResponse`, the same shape as a message), so it replaces the message instead of adding one;
`/user/queue/messages` stays for new messages only. Message responses carry `editedAt` (null if never edited) and `deleted`; `lastMessage` in the inbox carries `deleted` too.

**Deleting a conversation.** `DELETE /conversations/{id}` (`204`, safe to repeat) deletes it **for you only**. Everything in it up to now leaves your inbox and your history and is marked read; the other person
keeps their copy and is not told. If either of you writes again, the conversation comes back in your inbox with **only the new messages** (the deleted history stays deleted), and starting a chat with
the same person reuses it. Technically each side remembers the newest message it deleted (`user_one_cleared_before`, `user_two_cleared_before`): messages up to there are hidden from that person in the inbox,
the history, the previews and the unread counts. It works whatever the other account's state (deactivated, blocked): it only changes your own view. Your own old messages in a deleted conversation can no longer be edited or deleted (`404`).

**Running several instances.** Pushes (notifications and messages) are published to the Redis channel `ws:user-push` and every instance
delivers them to the sessions it holds, so a user receives them on whichever instance they are connected to. This is on by default
(`WS_REDIS_RELAY=false` turns it off for a single instance). Redis does not have to be up at boot: the app starts, delivers pushes to
its own sessions only, and joins the relay as soon as Redis is reachable. Put the load balancer in front with no sticky sessions
requirement for pushes; the WebSocket itself stays on the instance that accepted it. Messages sent *over* the socket are handled by
the instance holding that socket and relayed to the recipient the same way.

### Neon

Use Neon's pooled connection string (host contains `-pooler`) as `DB_URL` and its direct string as `MIGRATION_DB_URL`, both with `?sslmode=require`; see `.env.example`. Use an **empty** database: V1 creates the whole schema. Keep the app in the **same cloud region** as the Neon project: every query is a network round trip that holds a pooled connection, so per-instance throughput is about `DB_POOL_SIZE / (queries per request x latency)` (see `loadtest/README.md`). Neon's pooler accepts many client connections, so `DB_POOL_SIZE` can be raised (30-50) when you run few instances.

A database that already has the complete schema from the older `ddl-auto` setup (e.g. a local `xclone`) must be adopted once with `FLYWAY_BASELINE_ON_MIGRATE=true`; a non-empty database without it fails at startup rather than being half-adopted.

R2 is optional for local development. Without `R2_ACCOUNT_ID`/`R2_ACCESS_KEY`/`R2_SECRET_KEY`, everything works except media endpoints, which return `503`.

Run the tests with `./mvnw test`. They need the `xclone_test` database, and R2 is mocked.

## Docker and CI

Deploying the whole thing (backend image, static frontend, CORS, R2, a post-deploy checklist): see [docs/deploy.md](docs/deploy.md).

```bash
docker build -t xclone-backend .
docker run -p 8080:8080 --env-file .env xclone-backend   # JWT_SECRET is required; see .env.example
```

The image is a two-stage build (JDK builds, JRE runs as a non-root user). `docker-compose.yml` only starts a local Redis.
GitHub Actions (`.github/workflows/ci.yml`) runs `./mvnw verify` against Postgres and Redis service containers on every push to
`main` and every pull request, then checks that the Docker image builds. Tests need no secrets.

## Health checks

`GET /actuator/health` (public, no details) is the only actuator endpoint exposed.
- `/actuator/health/liveness`: the process is running. Use it to decide when to restart (the Docker `HEALTHCHECK` uses it).
- `/actuator/health/readiness`: the database is reachable. Use it as the load balancer / orchestrator readiness probe. While the
  database is unreachable the probe does not answer 200 (it can hang until the connection timeout, so configure a probe timeout).

Redis is deliberately not part of health: rate limiting and WebSocket relay degrade gracefully without it, so a Redis outage should not
take instances out of rotation.

## Cloudflare R2 setup

Pictures (post images, avatars, banners) are stored in a Cloudflare R2 bucket. The browser uploads straight to the bucket with a short-lived signed address
the backend hands out, and visitors load the pictures from the bucket's public address. R2's free tier (10 GB of storage, free downloads) is plenty to start,
but Cloudflare asks for a payment method when you enable R2. Cloudflare renames menus now and then, so the names below may differ a little.

1. **Enable R2.** Sign in at https://dash.cloudflare.com, open **R2 Object Storage** in the sidebar and follow the prompts to enable it.
2. **Create a bucket.** *Create bucket*, name it (lowercase letters, digits and dashes, e.g. `xclone-media`), leave the location on automatic.
3. **Turn on public access** so visitors can see the pictures: open the bucket > **Settings** > **Public access** > **R2.dev subdomain** > *Allow access*.
   Copy the **Public R2.dev Bucket URL** (like `https://pub-1234abcd….r2.dev`), with no slash and no folder at the end. This is `R2_PUBLIC_BASE_URL`.
   (r2.dev addresses are rate-limited and meant for development. For production attach your own domain under *Custom domains* instead.)
4. **Allow your website to upload (CORS).** Same page: **CORS Policy** > *Add CORS policy* and paste the rule below, with the address your frontend runs on
   (`http://localhost:5173` while developing; add the real site address too when you deploy):

   ```json
   [{ "AllowedOrigins": ["http://localhost:5173"], "AllowedMethods": ["PUT"], "AllowedHeaders": ["content-type", "content-length"], "MaxAgeSeconds": 3600 }]
   ```

5. **Create an API token.** R2 overview page > **Manage API tokens** > *Create API token*. Permission **Object Read & Write**, limited to your bucket. After creating it Cloudflare
   shows the **Access Key ID** and the **Secret Access Key**: copy the secret now, it is shown only once. (If you lose it, delete the token and make a new one.)
   Your **Account ID** is the 32-character id on the R2 overview page (it is also in the address of the dashboard).
6. **Give the backend the settings.** In IntelliJ: Run > Edit Configurations > your Spring Boot configuration > Environment variables (on a host: its environment or
   secrets settings). **Never write them into a file in the repository.**

   ```
   R2_ACCOUNT_ID=<the 32-character Account ID>
   R2_ACCESS_KEY=<Access Key ID>
   R2_SECRET_KEY=<Secret Access Key>
   R2_BUCKET=<the bucket name, e.g. xclone-media>
   R2_PUBLIC_BASE_URL=<the public bucket URL from step 3>
   ```

7. **Restart the backend and check it.** Sign in as an admin, open **Settings > Image storage** and press **Check image storage**. It checks the settings, uploads a tiny test image from the
   server, reads it back through the public address, then repeats the upload from your browser (that part tests the CORS rule) and deletes the test image. Each step shows a tick or a cross with
   what to change. When everything is green, upload a profile picture or a post image to see it for real.

| Failed step | Usually means |
|---|---|
| Storage settings | one of `R2_ACCOUNT_ID`, `R2_ACCESS_KEY`, `R2_SECRET_KEY` is not set in the process that is running (restart it after changing the environment) |
| Upload a test image: could not reach … | the Account ID is wrong (the address is `https://<account id>.r2.cloudflarestorage.com`), or no internet access |
| … `InvalidAccessKeyId` / `SignatureDoesNotMatch` | the Access Key ID or the Secret Access Key was copied wrongly, or belongs to another account |
| … `AccessDenied` | the token is not **Object Read & Write**, or is limited to a different bucket |
| … `NoSuchBucket` | `R2_BUCKET` does not match the bucket's name exactly |
| Public address (HTTP 403 / 404) | public access is not enabled for the bucket, or `R2_PUBLIC_BASE_URL` is wrong (pictures would show as broken) |
| Image uploaded from your browser | the bucket's CORS rule does not allow the address your site runs on (the check shows the exact rule to paste) |

For another S3-compatible server (for example a local MinIO) set `R2_ENDPOINT` to its address; leave it empty for Cloudflare.

### Image upload flow

1. `POST /api/media/upload-url` `{ "contentType": "image/png", "contentLength": 12345 }` returns `{ key, uploadUrl, headers, publicUrl, expiresAt }`.
2. `PUT` the file bytes to `uploadUrl` with the returned `headers` (Content-Type must match).
3. Use `key` in `mediaKeys` when creating a post, or as `avatarKey`/`bannerKey` in `PATCH /api/users/me`.

Allowed types are jpeg, png, webp and gif, up to 5 MB by default. The server only accepts keys under the caller's own `users/{id}/` prefix that actually exist in the bucket.

## API

All endpoints are under `/api`. Send `Authorization: Bearer <accessToken>` for authenticated calls. Errors use RFC 9457 problem JSON.

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/register` | – | `{username, email, password, displayName}` |
| POST | `/auth/login` | – | `{usernameOrEmail, password}` |
| POST | `/auth/refresh` | – | `{refreshToken}`; rotates the token, and reusing an old one revokes all sessions |
| POST | `/auth/logout` | – | `{refreshToken}` |
| POST | `/auth/verify-email` | – | `{token}` from the emailed link; 400 if invalid, expired or already used (204 on success) |
| POST | `/users/me/verify-email` | ✓ | resend the verification email; 409 if already verified |
| POST | `/auth/forgot-password` | – | `{email}`; always 204 so it can't be used to find accounts; emails a reset link (valid 1h) to active accounts |
| POST | `/auth/reset-password` | – | `{token, newPassword}`; sets the password and signs out every session; 400 if invalid, expired or already used |
| GET / PATCH | `/users/me` | ✓ | PATCH `{displayName?, bio?, avatarKey?, bannerKey?, protectedAccount?}`; `""` clears. See *Protected accounts* below |
| GET | `/trending/hashtags?hours=&limit=` | – | tags that are rising: see *Trending* below; `{name, postCount, userCount}` (counts are for the recent window) |
| GET | `/users/search?q=` | – | prefix match on username / display name |
| GET | `/users/me/blocks` | ✓ | users you blocked, paged |
| GET | `/users/{username}` | optional | profile + counts + `followedByMe` + `blockedByMe` + `mutedByMe` |
| POST / DELETE | `/users/{username}/follow` | ✓ | idempotent. POST answers `204` (now following) or `202` (account is protected: request pending approval); DELETE unfollows or withdraws a pending request |
| GET | `/users/me/follow-requests` | ✓ | pending requests to follow you, paged |
| POST | `/users/me/follow-requests/{username}/approve`, `/deny` | ✓ | `404` if there is no pending request from that user |
| POST / DELETE | `/users/{username}/block` | ✓ | idempotent; removes follows both ways. Blocked pairs can't follow, like or reply to each other or see each other's posts (403), and are hidden from each other's reply and follower lists |
| POST / DELETE | `/users/{username}/mute` | ✓ | idempotent; silent and one-way: their posts (and reposts of them) leave your timeline, and notifications from them are hidden and not pushed live. Follows, replies, likes and DMs are unaffected, and they are not told. Unmuting restores everything |
| GET | `/users/me/mutes` | ✓ | users you muted, paged |
| GET | `/users/suggestions?limit=` | ✓ | who to follow (default 10, max 20): accounts followed by your most recent 200 follows, ranked by `mutualFollowCount`; excludes you, who you follow, blocks (either way), mutes and inactive accounts. When that yields fewer than `limit`, it is filled with the most-followed accounts (`mutualFollowCount` 0; ranking cached 10 min, `SUGGESTIONS_POPULAR_CACHE_TTL`) |
| GET | `/users/{username}/followers`, `/following` | – | paged |
| GET | `/users/{username}/posts`, `/replies`, `/likes` | optional | paged |
| POST | `/posts` | ✓ | `{content?, mediaKeys?, replyToId?, quotedPostId?, replyPolicy?}`. `replyPolicy` (`EVERYONE` default, `FOLLOWING` = accounts the author follows, `MENTIONED` = accounts @mentioned in the post) is set on top-level posts only (400 on replies and quotes). Replying against it is 403; the author can always reply |
| POST | `/posts/thread` | ✓ | `{posts: [{content?, mediaKeys?}, ...], replyPolicy?}`, 2–25 posts created atomically; each replies to the previous |
| PATCH | `/posts/{id}/reply-policy` | ✓ | `{replyPolicy}`; author-only, top-level posts only; applies to future replies |
| GET | `/posts/{id}/thread` | optional | the conversation's top-level post plus the author's own chained posts, oldest first (others' replies excluded) |
| GET / DELETE | `/posts/{id}` | optional / ✓ | delete is author-only (soft delete) |
| GET | `/posts/{id}/replies` | optional | paged, oldest first. Every post response includes `conversationId`, `replyPolicy` and `canReply` (for the viewer), plus `likedByMe`, `repostedByMe` and `bookmarkedByMe` for the post shown |
| POST / DELETE | `/posts/{id}/like` | ✓ | idempotent |
| GET | `/posts/{id}/likes` | – | users who liked, paged |
| GET | `/posts/{id}/reposts` | – | users who reposted (plain reposts, newest first), paged; blocked, inactive and hidden protected accounts left out |
| GET | `/notifications` | ✓ | follow / like / reply / mention notifications, newest first, paged; hides blocked or inactive actors |
| GET | `/notifications/unread-count` | ✓ | |
| POST | `/notifications/read`, `/notifications/{id}/read` | ✓ | mark all / one as read |
| DELETE | `/notifications/{id}` | ✓ | |
| GET | `/timeline` | ✓ | your posts + people you follow, newest first (the **Following** tab) |
| GET | `/timeline/for-you` | ✓ | the ranked **For you** feed: popular recent posts mixed with the people you follow. Paged with `?cursor=&limit=` like the others, but the cursor is opaque (see below) and the feed ends after 200 posts |
| POST | `/media/upload-url` | ✓ | see above |

**Protected accounts.** With `protectedAccount: true`, following needs the owner's approval, and the account's posts, replies, likes tab, follower and
following lists are visible only to the owner and approved followers (everyone else, including anonymous visitors, gets `403`; the profile itself stays public and
shows `protectedAccount` and `followRequestedByMe`). Existing followers stay. Their posts are also left out of search, hashtag pages, trending, reply lists and
quote embeds for everyone else, and cannot be reposted or quoted by anyone. Replies and mentions from a protected account do not notify people who cannot see them.
The owner is notified of requests (`FOLLOW_REQUEST`). Turning protection off approves all pending requests.

**How "For you" is ranked.** One SQL query (`PostRepository.findForYouIds`, constants at the top of `PostService`):

- *Candidates:* the latest 2000 top-level posts from the last 7 days that you may see: the author is active, protected authors only if they are you or you follow them, no blocks either way, no muted authors, not deleted. Replies and repost rows are never candidates; your own posts are.
- *Score:* `(likes + 2·reposts + 3·replies + 1) / (age in hours + 2)^1.5`, times a boost: **2.0** for you and the people you follow, **1.3** for people followed by someone you follow, otherwise 1.0. So engagement wins, newer wins, and your circle is favoured without hiding strangers' popular posts.
- *Variety:* at most 3 posts per author.
- *Paging:* ranking depends on the clock, so the cursor packs the minute the feed was scored at and how many posts were served (`minute * 1000 + offset`); every page of one feed is scored at that same minute. A cursor older than an hour, or invalid, simply starts a new feed.
- A post you write appears at the top of the feed you are looking at at once, but the next time the feed is reloaded it sits where its score puts it (a new post has no likes yet); it is always on your profile and in Following.

**Pagination.** Paged endpoints accept `?cursor=&limit=` (default 20, max 50) and return `{ items, nextCursor }`. Pass `nextCursor` back to get the next page; `null` means there are no more pages.

## Trending

`GET /api/trending/hashtags?hours=&limit=` ranks tags by how far their recent use rises above **their own** normal level, not by raw volume (`hashtag/TrendScorer.java`; the full design, with worked examples and trade-offs, is in [docs/trending.md](docs/trending.md)):

- The **recent window** is the last `hours` (default 6, max 168). Only live posts of active, public accounts count. The tag's normal level is its average over the `floor(168 / hours)` windows of the same length before it (at least one).
- `score = participation + 0.5 * volume + 0.5 * momentum`, each a conservative log growth rate `ln((observed + s) / (expected + s)) - sqrt(1/(observed + s) + 1/(expected + s))`:
  *participation* compares distinct people with the usual number of distinct people per window, *volume* compares posts (an account counts for at most 3 per window) with the usual number, *momentum* compares the newer half of the window with the older half.
  Activity that matches the norm scores around zero, a fading tag scores below, thin evidence is pulled down, and a tag with no history is compared with a prior of `s = 3` rather than with zero.
- Accounts younger than 3 days count for less (down to a quarter for a brand-new one), so a burst of just-created accounts ranks lower than the same number of established ones.
- A tag needs at least `TRENDING_MIN_AUTHORS` (default 2) different authors in the window before it can trend. The response shape is unchanged: `{name, postCount, userCount}` are the plain counts in the window.
- Settings (`application.yaml`, environment): `TRENDING_MIN_AUTHORS`, `TRENDING_MAX_POSTS_PER_AUTHOR` (3), `TRENDING_SMOOTHING` (3.0), `TRENDING_MATURE_ACCOUNT_DAYS` (3, 0 = no discount), `TRENDING_NEW_ACCOUNT_WEIGHT` (0.25).
- **Cached in Redis** (`TRENDING_CACHE_TTL`, default 30 s, `0` turns it off): the top 50 for each window length is stored under `trending:v1:<hours>`, shared by all instances, and a smaller `limit` is a prefix of it, so one query per window length per TTL serves every page view. On a miss one caller (a short Redis lock) computes while the others wait a moment for its result. Results may be up to one TTL old; nothing needs to invalidate them. Redis is an optimisation, not a dependency: if it is unreachable the list is computed directly (the first request waits for the Redis connect timeout, `REDIS_CONNECT_TIMEOUT`, 2 s by default; Redis is then not tried for 30 s). Without the cache the cost is one query over the posts of the last 8 days (see the design doc for measurements).

## Deactivated accounts

`POST /users/me/deactivate` signs the person out everywhere and hides them. Nothing is deleted; **signing in again** (`POST /auth/login` with the right password) brings everything back exactly as it was.
While an account is deactivated it is a blank **"XClone user"** to everyone else:

- **Profile:** `GET /users/{username}` answers `200` with `unavailable: true`, `displayName: "XClone user"`, an empty `username`, and no bio, picture, banner, join date or counts. The app shows a plain page that says the account is unavailable.
- **Posts:** their posts, replies, reposts and likes are not shown anywhere (feeds, For you, search, hashtags, someone else's reply thread, profile tabs, likes and bookmarks lists, quotes of them), and `GET /posts/{id}` answers `404`. Counters such as a post's reply count are not recomputed.
- **Lists:** wherever they would be listed (followers, following, who liked a post, @mentions inside other people's posts, message senders) they appear as a `UserSummary` with `unavailable: true`, an empty `username`, no picture and the name "XClone user". Clients must not link to them. Follower and following counts do not change.
- **Direct messages:** a conversation with them stays in the inbox as "XClone user" and its history (and unread count) stays readable, but nothing can be sent, edited or deleted in it (`403`).
- **Actions by handle** (follow, block, mute, report, start a conversation) answer `404`. They no longer appear in user search or the blocked and muted lists (a block or mute is kept and returns with them). Notifications from them and their follow requests stay hidden.
- **Admins** still see who a report is about, whatever the status (`reportedUserStatus: "DEACTIVATED"`).
- Suspended accounts behave the same way in lists and posts, but their profile address still answers `404`.

## Moderating reports

Users can report accounts (`POST /users/{username}/report`) and posts (`POST /posts/{id}/report`). A report stores who reported, who or what, the
reason, and a review status (`OPEN`, `DISMISSED`, `RESOLVED`). The reported account or post is unchanged until an admin acts; the admins are alerted (see below).

**Becoming an admin** is done in the database; there is no way to do it through the API. Run this once per person (psql locally, or Neon's SQL editor):

```sql
update users set is_admin = true where username = 'their_username';   -- revoke with false
```

The flag is read from the database on every admin request, so granting and revoking take effect immediately (no restart, no new login). `GET /users/me`
returns `admin` so the app can show the "Reports" page, which lives at `/admin/reports` in the frontend.

| Method | Path | Notes |
|---|---|---|
| GET | `/admin/reports/users`, `/admin/reports/posts` | `?status=OPEN` (default) / `HANDLED` (dismissed or resolved) / `ALL`; newest first, paged. Each row has the reporter, reason, status, who handled it and when, and `totalReports` (how many reports that account / post has received) |
| PATCH | `/admin/reports/users/{id}`, `/admin/reports/posts/{id}` | `{status, note?}`; `OPEN` reopens. Records the admin and the time; dismissing or resolving tells the reporter. 204 |
| POST | `/admin/posts/{postId}/remove` | removes a reported post (the same soft delete as an author deleting it) and resolves all its open reports. Optional body `{note}`. Tells the author and the reporters. Safe to repeat. 204 |
| POST | `/admin/users/{userId}/suspend` | optional `{note}`. Sets status `SUSPENDED`: all sessions end, sign-in answers `403 Account suspended`, the profile and posts are hidden (404 / absent from timelines and search), open reports about the account are resolved, the user is emailed. Reversible. 204 |
| POST | `/admin/users/{userId}/unsuspend` | lifts it and emails the user. 204 |
| POST | `/admin/users/{userId}/remove` | optional `{note}`. Permanent: the same anonymization as a user deleting their own account, without the password. The user is emailed first. Admins and yourself cannot be targeted. 204 |

Everything under `/admin` answers `403 Admins only` to other users and `401` to anonymous callers. Admins see the content of a reported post even when it belongs to a
protected account: that is what reviewing a report needs, so grant the flag sparingly.

**Notifications.** Moderation messages are in-app notifications with no actor (so no admin's name is shown; `actor` is `null` and the text is in `detail`) plus an email,
because a suspended or removed user cannot read the app:

| Event | Who is told |
|---|---|
| A report arrives | every active admin, but only for the *first* open report about an account or post (more reports raise the count on the Reports page instead of sending more alerts) |
| A post is removed | its author, with the admin's note if there is one |
| A report is dismissed, resolved or closed by an action | the reporter ("action taken" / "no action taken"; the note is never shared with reporters) |
| Account suspended, unsuspended, removed | that user, by email only |

Suspension is not deactivation: deactivating is the user's own choice and signing in undoes it; a suspension can only be lifted by an admin. Emails are sent after the action commits and never make it fail
(without `SPRING_MAIL_HOST` they are logged). The frontend does not show these notifications yet (that comes with the Notifications page); the emails and the API work now.

## Email

Registering and changing your email send a verification link (`emailVerified` in the user response; accounts that
existed before this feature count as verified), and "forgot password" sends a reset link. Moderation notices (post removed, account suspended ...) are emailed too.
Nothing is blocked for unverified users yet. Links point to `FRONTEND_URL/verify-email?token=…` and `FRONTEND_URL/reset-password?token=…`
(`FRONTEND_URL` defaults to `http://localhost:5173`; set it if the frontend runs elsewhere). Sender: `MAIL_FROM`.

How sending behaves: an email is queued once the database transaction has **committed** and is delivered on a background thread, so a slow or
broken mail server never delays or fails a request (registration still works; the failure is logged as `Could not send email to …`). SMTP connections
give up after 5 seconds (`MAIL_TIMEOUT_MS`).

**Without `SPRING_MAIL_HOST`** the app does not send anything: it logs each message, link included (look for `Email not sent (no SMTP configured)` in the console).
That is what the automated tests and CI use (the browser tests read the links from that log), and it is also an easy way to try the app with no mail account at all.

### Sending real email with Gmail (App Password)

Good for development and a small test. A personal Gmail account allows roughly 500 messages a day, and Google may block logins from some hosting
providers, so use a transactional email service for production (see below).

1. Turn on **2-Step Verification** for the Google account: https://myaccount.google.com/security
2. Create an **App Password**: https://myaccount.google.com/apppasswords (name it e.g. "XClone"). Google shows a 16-character password once. Your normal Google
   password does **not** work here (that is the `535 Username and Password not accepted` error).
3. Give the backend these environment variables. In IntelliJ: Run > Edit Configurations > your Spring Boot configuration > Environment variables.
   On a host: its environment or secrets settings. **Never write them into a file in the repository.**

   ```
   SPRING_MAIL_HOST=smtp.gmail.com
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=your.address@gmail.com
   SPRING_MAIL_PASSWORD=<the 16-character App Password, spaces removed>
   MAIL_FROM=your.address@gmail.com
   ```

   Port 587 with STARTTLS and a login are already the defaults. `MAIL_FROM` should be the same Gmail address (Gmail rewrites any other sender to it).
4. Restart the backend, sign in as an admin, open **Settings > Email delivery** and press **Send test email**. It sends one message to your own address and shows
   the mail server's real answer if something is wrong. Then register a new account and check that the verification email arrives.

| What you see | Usually means |
|---|---|
| `535 Username and Password not accepted` | the normal Google password was used, or the App Password was copied wrongly (remove the spaces); or 2-Step Verification is off |
| `534 Application-specific password required` | same: create an App Password |
| `Email is not configured` | `SPRING_MAIL_HOST` is not set in the process that is running (restart it after changing the environment) |
| a timeout or `Could not connect` | port 587 blocked by the network or a firewall; try another network |
| "Sent" but nothing in the inbox | look in Spam; Gmail may also hold the first messages a new app sends |

### Production

Use a transactional email service (Brevo, Resend, Amazon SES, Mailgun, Postmark; several have free tiers). **No code change**: only these variables, set in the hosting
platform, with that provider's SMTP host and credentials:

```
SPRING_MAIL_HOST=<the provider's SMTP host>
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=<provider login>
SPRING_MAIL_PASSWORD=<provider SMTP key>
MAIL_FROM=noreply@yourdomain.com
FRONTEND_URL=https://your-site.example
```

To reach any recipient and stay out of spam, verify your sending domain with the provider (a few DNS records: SPF and DKIM). Mail is deliberately not part of
`/actuator/health`, so a mail outage never makes the app report DOWN.
