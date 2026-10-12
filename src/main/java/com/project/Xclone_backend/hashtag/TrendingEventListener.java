package com.project.Xclone_backend.hashtag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Consumes the post events and asks {@link TrendingRefresher} to refresh. The consumer group is shared by all instances, so each event is handled
 * by one of them, and the cache it refreshes is the one in Redis that they all read. The content of an event does not matter here (any of them can
 * change the ranking), so a message that cannot be read is no different from any other. Only events from now on are wanted, never a replay.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
@RequiredArgsConstructor
class TrendingEventListener {

    private static final Logger log = LoggerFactory.getLogger(TrendingEventListener.class);

    private final TrendingRefresher refresher;

    @KafkaListener(id = "trending-refresh", groupId = "trending-refresh", topics = "${app.kafka.topic:post-events}")
    void onPostEvent(String payload) {
        log.trace("Post event: {}", payload);
        refresher.request();
    }
}
