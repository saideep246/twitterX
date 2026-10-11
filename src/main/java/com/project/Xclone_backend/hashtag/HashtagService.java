package com.project.Xclone_backend.hashtag;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.Xclone_backend.hashtag.HashtagDtos.TrendCandidate;
import com.project.Xclone_backend.hashtag.HashtagDtos.TrendingHashtag;


@Service
public class HashtagService {

    /**
     * '#' then letters, digits or '_', with at least one letter (so "#2024" is not a tag). Must not follow a word
     * character, '#', '&' or '/', which skips "abc#x", "##x", HTML entities like "&#39;" and URL fragments.
     */
    private static final Pattern HASHTAG =
            Pattern.compile("(?<![\\p{L}\\p{N}_#&/])#([\\p{L}\\p{N}_]*\\p{L}[\\p{L}\\p{N}_]*)");

    public static final int TRENDING_DEFAULT_HOURS = 6;
    /** The days before the recent window that show what is normal for a tag. */
    public static final int TRENDING_BASELINE_HOURS = 7 * 24;
    /** Candidates handed to the scorer, chosen in SQL by participation lift (so rising tags are not crowded out by merely big ones). */
    static final int TRENDING_CANDIDATES = 500;
    public static final int TRENDING_MAX_HOURS = 168;
    public static final int TRENDING_DEFAULT_LIMIT = 10;
    public static final int TRENDING_MAX_LIMIT = 50;

    private final HashtagRepository hashtagRepository;
    private final TrendingProperties trendingProperties;
    private final Clock clock;
    private final TrendingCache trendingCache;

    @Autowired
    public HashtagService(HashtagRepository hashtagRepository, TrendingProperties trendingProperties, TrendingCache trendingCache) {
        this(hashtagRepository, trendingProperties, Clock.systemUTC(), trendingCache);
    }

    /** With the time source given and no cache, so tests need not depend on the real clock or on Redis. */
    HashtagService(HashtagRepository hashtagRepository, TrendingProperties trendingProperties, Clock clock) {
        this(hashtagRepository, trendingProperties, clock, TrendingCache.disabled());
    }

    HashtagService(HashtagRepository hashtagRepository, TrendingProperties trendingProperties, Clock clock, TrendingCache trendingCache) {
        this.hashtagRepository = hashtagRepository;
        this.trendingProperties = trendingProperties;
        this.clock = clock;
        this.trendingCache = trendingCache;
    }

    /**
     * Tags whose use in the last {@code hours} (default 6, max 7 days) rises most above their normal level, needing at least
     * {@code app.trending.min-authors} different authors. The normal level comes from the {@code floor(168 / hours)} windows of the same length
     * before it (at least one). See {@link TrendScorer} for the score.
     */
    public List<TrendingHashtag> trending(Integer hours, Integer limit) {
        int h = hours == null || hours <= 0 ? TRENDING_DEFAULT_HOURS : Math.min(hours, TRENDING_MAX_HOURS);
        int n = limit == null || limit <= 0 ? TRENDING_DEFAULT_LIMIT : Math.min(limit, TRENDING_MAX_LIMIT);
        // The cache holds the full top list for this window length; a smaller limit is a prefix of it. No database transaction is held while
        // waiting on Redis: the one read-only query inside needs none.
        List<TrendingHashtag> top = trendingCache.getOrCompute(h, () -> computeTrending(h));
        return top.size() <= n ? top : List.copyOf(top.subList(0, n));
    }

    /** The top {@link #TRENDING_MAX_LIMIT} for a window of {@code h} hours, straight from the database. */
    private List<TrendingHashtag> computeTrending(int h) {
        TrendingProperties p = trendingProperties;
        Instant now = clock.instant();
        Duration window = Duration.ofHours(h);
        int slices = Math.max(1, TRENDING_BASELINE_HOURS / h);
        Instant recentSince = now.minus(window);
        Instant halfSince = now.minus(Duration.ofMinutes(h * 30L));
        Instant since = recentSince.minus(window.multipliedBy(slices));
        List<TrendCandidate> candidates = hashtagRepository.findTrendRows(since, recentSince, halfSince, now, window.toSeconds(),
                p.maxPostsPerAuthor(), p.matureAccountDays(), p.newAccountWeight(), p.smoothing(), slices, p.minAuthors(), TRENDING_CANDIDATES)
                .stream()
                .map(r -> new TrendCandidate(r.getName(), r.getRecentPosts(), r.getRecentAuthors(), r.getPosts(), r.getAuthors(),
                        r.getNewerAuthors(), r.getOlderAuthors(), r.getBasePostsSum() / slices, r.getBaseAuthorsSum() / slices))
                .toList();
        return TrendScorer.rank(candidates, p.smoothing(), TRENDING_MAX_LIMIT);
    }

    /** Distinct normalized tags in order of first appearance. */
    public static Set<String> extract(String content) {
        Set<String> names = new LinkedHashSet<>();
        if (content == null) {
            return names;
        }
        Matcher m = HASHTAG.matcher(content);
        while (m.find()) {
            names.add(normalize(m.group(1)));
        }
        return names;
    }

    /** Lowercases and drops a leading '#', so "#Java", "java" and "JAVA" all become "java". */
    public static String normalize(String raw) {
        String name = raw.strip();
        if (name.startsWith("#")) {
            name = name.substring(1);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /** Returns the managed hashtags for the tags in {@code content}, creating any that don't exist yet. */
    @Transactional
    public Set<Hashtag> resolve(String content) {
        Set<String> names = extract(content);
        if (names.isEmpty()) {
            return Set.of();
        }
        names.forEach(hashtagRepository::insertIfAbsent);
        return new HashSet<>(hashtagRepository.findByNameIn(names));
    }
}
