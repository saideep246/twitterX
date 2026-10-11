package com.project.Xclone_backend.hashtag;

import java.util.Collection;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;


public interface HashtagRepository extends JpaRepository<Hashtag, Long> {

    /** Idempotent and safe under concurrent posts introducing the same tag. */
    @Modifying
    @Query(value = "insert into hashtags (name) values (:name) on conflict (name) do nothing", nativeQuery = true)
    void insertIfAbsent(String name);

    List<Hashtag> findByNameIn(Collection<String> names);

    /** One tag's aggregated activity; see {@link #findTrendRows}. */
    interface TrendRow {
        String getName();

        long getRecentPosts();

        long getRecentAuthors();

        double getPosts();

        double getAuthors();

        double getNewerAuthors();

        double getOlderAuthors();

        /** Sum over the baseline slices of the limited, weighted posts; divide by the number of slices for the per-window expectation. */
        double getBasePostsSum();

        /** Sum over the baseline slices of the weighted distinct authors of each slice. */
        double getBaseAuthorsSum();
    }

    /**
     * Activity per tag for trending, in one pass over the posts of live, public, active accounts in {@code [since, now]}.
     *
     * <p>Time is cut into half-open pieces, all aligned to {@code recentSince}: the recent window {@code [recentSince, now]}, of which the newer half
     * is {@code [halfSince, now]}, and before it {@code slices} baseline slices of {@code sliceSeconds} each, slice 0 being
     * {@code [recentSince - slice, recentSince)}, slice 1 the one before it, and so on (a post exactly on a boundary belongs to the later
     * piece). {@code since} must equal {@code recentSince - slices * sliceSeconds}.
     *
     * <p>The query is one pass: a first aggregation gives one row per (tag, piece, author), a second folds those into one row per tag with
     * {@code FILTER} (no self-join, so a bad row estimate on a table without fresh statistics cannot turn it into a nested loop).
     * Distinct authors are counted per (tag, piece) with a hash aggregate rather than {@code count(distinct)}. The baseline distinct-author
     * figure is the sum over slices of that slice's distinct authors, so dividing by {@code slices} gives the expected number of different people
     * in a window of this length: a plain distinct count over the whole week would be larger than any single window and mislead.
     *
     * <p>Each author is weighted by account age ({@code 1} at {@code matureDays} old, down to {@code newWeight} for a new account; no discount when
     * {@code matureDays} is 0) and counts for at most {@code cap} posts per piece. Tags with fewer than {@code minAuthors} recent authors are left
     * out. Candidates are ordered by participation lift, the dominant term of the score, so a rising tag cannot be crowded out by merely big ones.
     */
    @Query(value = """
            with cell as (
              select ph.hashtag_id as tag_id, p.author_id,
                     case when p.created_at >= cast(:recentSince as timestamptz) then -1
                          else cast(ceil(extract(epoch from (cast(:recentSince as timestamptz) - p.created_at)) / :sliceSeconds) as integer) - 1
                     end as slice,
                     case when cast(:matureDays as double precision) <= 0 then 1.0
                          else greatest(cast(:newWeight as double precision), least(1.0,
                                 extract(epoch from (cast(:now as timestamptz) - u.created_at)) / (86400.0 * cast(:matureDays as double precision))))
                     end as w,
                     count(*) as c,
                     count(*) filter (where p.created_at >= cast(:halfSince as timestamptz)) as newer
              from posts p
              join post_hashtags ph on ph.post_id = p.id
              join users u on u.id = p.author_id
              where p.deleted = false and u.status = 'ACTIVE' and u.protected_account = false
                and p.created_at >= cast(:since as timestamptz) and p.created_at <= cast(:now as timestamptz)
              group by ph.hashtag_id, p.author_id, 3, 4
            ),
            agg as (
              select tag_id,
                     cast(sum(c) filter (where slice = -1) as bigint) as recent_posts,
                     cast(count(*) filter (where slice = -1) as bigint) as recent_authors,
                     sum(w * least(c, :cap)) filter (where slice = -1) as posts,
                     sum(w) filter (where slice = -1) as authors,
                     coalesce(sum(w) filter (where slice = -1 and newer > 0), 0) as newer_authors,
                     coalesce(sum(w) filter (where slice = -1 and newer < c), 0) as older_authors,
                     coalesce(sum(w * least(c, :cap)) filter (where slice >= 0), 0) as base_posts_sum,
                     coalesce(sum(w) filter (where slice >= 0), 0) as base_authors_sum
              from cell
              group by tag_id
              having count(*) filter (where slice = -1) >= :minAuthors
            )
            select h.name as "name", a.recent_posts as "recentPosts", a.recent_authors as "recentAuthors",
                   cast(a.posts as double precision) as "posts", cast(a.authors as double precision) as "authors",
                   cast(a.newer_authors as double precision) as "newerAuthors", cast(a.older_authors as double precision) as "olderAuthors",
                   cast(a.base_posts_sum as double precision) as "basePostsSum",
                   cast(a.base_authors_sum as double precision) as "baseAuthorsSum"
            from agg a
            join hashtags h on h.id = a.tag_id
            order by ln((a.authors + :smoothing) / (a.base_authors_sum / :slices + :smoothing)) desc,
                     a.recent_authors desc, h.name asc
            limit :candidates
            """, nativeQuery = true)
    List<TrendRow> findTrendRows(Instant since, Instant recentSince, Instant halfSince, Instant now, long sliceSeconds, int cap,
            double matureDays, double newWeight, double smoothing, int slices, long minAuthors, int candidates);
}
