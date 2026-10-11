package com.project.Xclone_backend.hashtag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrendingPropertiesTest {

    @Test
    void validValuesAreKept() {
        TrendingProperties p = new TrendingProperties(5, 4, 2.5, 7, 0.5);
        assertThat(p).isEqualTo(new TrendingProperties(5, 4, 2.5, 7, 0.5));
        assertThat(p.minAuthors()).isEqualTo(5);
        assertThat(p.maxPostsPerAuthor()).isEqualTo(4);
        assertThat(p.smoothing()).isEqualTo(2.5);
        assertThat(p.matureAccountDays()).isEqualTo(7);
        assertThat(p.newAccountWeight()).isEqualTo(0.5);
    }

    @Test
    void invalidValuesFallBackToTheDefaults() {
        TrendingProperties defaults = TrendingProperties.defaults();
        assertThat(new TrendingProperties(0, 0, 0, -1, 0)).isEqualTo(defaults);
        assertThat(new TrendingProperties(-4, -1, -2.0, Double.NaN, 1.5)).isEqualTo(defaults);
        assertThat(new TrendingProperties(2, 3, Double.NaN, 3, Double.NaN)).isEqualTo(defaults);
    }

    @Test
    void theAccountAgeDiscountCanBeSwitchedOffWithZeroDays() {
        assertThat(new TrendingProperties(2, 3, 3.0, 0, 0.25).matureAccountDays()).isZero();
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        TrendingProperties d = TrendingProperties.defaults();
        assertThat(d.minAuthors()).isEqualTo(2);
        assertThat(d.maxPostsPerAuthor()).isEqualTo(3);
        assertThat(d.smoothing()).isEqualTo(3.0);
        assertThat(d.matureAccountDays()).isEqualTo(3);
        assertThat(d.newAccountWeight()).isEqualTo(0.25);
    }
}
