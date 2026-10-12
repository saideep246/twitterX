package com.project.Xclone_backend.postevent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;

/** Creates the topic at startup when it does not exist (and when the broker is reachable: the app starts either way). */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
class PostEventTopicConfig {

    @Bean
    NewTopics postEventsTopic(@Value("${app.kafka.topic:post-events}") String topic, @Value("${app.kafka.partitions:3}") int partitions) {
        return new NewTopics(TopicBuilder.name(topic).partitions(partitions).build());
    }
}
