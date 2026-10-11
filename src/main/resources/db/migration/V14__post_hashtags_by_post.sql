-- The primary key of post_hashtags leads with hashtag_id, so finding the tags of a post (editing a post, deleting an account, trending) had no
-- usable index and scanned the whole table; trending's join from posts to their tags could even pick a plan that did that once per post.
-- On a very large table, build it first without blocking writes (CREATE INDEX CONCURRENTLY IF NOT EXISTS ...); this statement is then a no-op.
CREATE INDEX IF NOT EXISTS idx_post_hashtags_post ON public.post_hashtags USING btree (post_id, hashtag_id);
