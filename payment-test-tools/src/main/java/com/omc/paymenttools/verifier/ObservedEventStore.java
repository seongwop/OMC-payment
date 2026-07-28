package com.omc.paymenttools.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.omc.paymenttools.verifier.model.ObservedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ObservedEventStore {

    private final ConcurrentMap<UUID, ConcurrentMap<String, ObservedEvent>> eventsByOrderId =
            new ConcurrentHashMap<>();

    // 동일 eventId 중복 소비를 제거하고 주문별 발행 이벤트 기록
    public void record(String topic, String eventId, UUID orderId, JsonNode payload) {
        eventsByOrderId
                .computeIfAbsent(orderId, ignored -> new ConcurrentHashMap<>())
                .putIfAbsent(eventId, new ObservedEvent(topic, eventId, orderId, payload, Instant.now()));
    }

    // 주문 단위 이벤트 발생 순서 조회
    public List<ObservedEvent> findByOrderId(UUID orderId) {
        ConcurrentMap<String, ObservedEvent> events = eventsByOrderId.get(orderId);
        if (events == null) {
            return List.of();
        }
        List<ObservedEvent> result = new ArrayList<>(events.values());
        result.sort(Comparator.comparing(ObservedEvent::observedAt));
        return List.copyOf(result);
    }

    // 독립된 테스트 실행을 위한 관찰 이벤트 초기화
    public void clear() {
        eventsByOrderId.clear();
    }
}
