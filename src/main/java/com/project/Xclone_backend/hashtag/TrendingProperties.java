package com.project.Xclone_backend.hashtag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for trending hashtags (see {@link TrendScorer} for how they are used). Values outside their valid range fall back to the default.
 *
 * @param minAuthors        how many different accounts must have used a tag in the recent window before it can trend at all
 * @param maxPostsPerAuthor an account counts for at most this many posts per window-length slice (recent window and every baseline slice), so one
 *                          account repeating a tag cannot inflate it
 * @param smoothing         prior pseudo-count: a tag is treated as if it normally had this many posts/authors per window, so a tag with no
 *                          history is neither infinitely surprising nor immune to small samples. Higher = demands more evidence
 * @param matureAccountDays accounts younger than this count for less (down to {@code newAccountWeight}), growing linearly to full weight; 0 turns
 *                          the discount off
 * @param newAccountWeight  the weight of an account that was created just now, in (0, 1]
 */
@ConfigurationProperties(prefix = "app.trending")
public record TrendingProperties(
        @DefaultValue("2") int minAuthors,
        @DefaultValue("3") int maxPostsPerAuthor,
        @DefaultValue("3.0") double smoothing,
        @DefaultValue("3") double matureAccountDays,
        @DefaultValue("0.25") double newAccountWeight) {

    public TrendingProperties {
        if (minAuthors < 1) {
            minAuthors = 2;
        }
        if (maxPostsPerAuthor < 1) {
            maxPostsPerAuthor = 3;
        }
        if (!(smoothing > 0)) {
            smoothing = 3.0;
        }
        if (!(matureAccountDays >= 0)) {
            matureAccountDays = 3;
        }
        if (!(newAccountWeight > 0 && newAccountWeight <= 1)) {
            newAccountWeight = 0.25;
        }
    }

    /** The defaults, for code and tests that do not go through Spring. */
    public static TrendingProperties defaults() {
        return new TrendingProperties(2, 3, 3.0, 3, 0.25);
    }
}
