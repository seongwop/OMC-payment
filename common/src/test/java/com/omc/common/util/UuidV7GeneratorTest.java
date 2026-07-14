package com.omc.common.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    // =========================================================================
    // [1] 생성된 UUID가 version 7인지 확인
    // =========================================================================

    @Test
    void generate_returnsVersion7() {
        UUID uuid = UuidV7Generator.generate();

        assertThat(uuid.version()).isEqualTo(7);
    }

    // =========================================================================
    // [2] RFC 4122 표준 variant(2) 확인
    // =========================================================================

    @Test
    void generate_returnsRfc4122Variant() {
        UUID uuid = UuidV7Generator.generate();

        assertThat(uuid.variant()).isEqualTo(2);
    }

    // =========================================================================
    // [3] 연속 생성한 값들이 시간순으로 정렬되는지 확인
    //     UUID v7은 상위 48비트가 밀리초 타임스탬프 → 문자열 비교 = 시간순 비교
    // =========================================================================

    @Test
    void generate_multipleValues_areSortedByCreationOrder() throws InterruptedException {
        UUID first  = UuidV7Generator.generate();
        Thread.sleep(1);
        UUID second = UuidV7Generator.generate();
        Thread.sleep(1);
        UUID third  = UuidV7Generator.generate();

        assertThat(first.toString()).isLessThan(second.toString());
        assertThat(second.toString()).isLessThan(third.toString());
    }

    // =========================================================================
    // [4] 멀티스레드 환경에서 중복 없이 전부 version 7로 생성되는지 확인
    // =========================================================================

    @Test
    void generate_isThreadSafe() throws InterruptedException {
        int THREAD_COUNT = 50;
        int PER_THREAD   = 20;
        Set<UUID> generated = ConcurrentHashMap.newKeySet();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < PER_THREAD; j++) {
                        generated.add(UuidV7Generator.generate());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(generated).hasSize(THREAD_COUNT * PER_THREAD);
        assertThat(generated).allMatch(uuid -> uuid.version() == 7);
    }
}
