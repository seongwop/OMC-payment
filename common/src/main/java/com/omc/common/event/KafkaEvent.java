package com.omc.common.event;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor
public abstract class KafkaEvent {

    private String eventId;
    private String eventType;
    private LocalDateTime occurredAt;

    protected KafkaEvent(String eventType) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.occurredAt = LocalDateTime.now();
    }
}
