package com.omc.common.util;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class UuidV7Generator {

    private UuidV7Generator() {
    }

    public static UUID generate() {
        long unixTimestampMillis = Instant.now().toEpochMilli() & 0xFFFFFFFFFFFFL;
        long randomA = ThreadLocalRandom.current().nextLong(1L << 12);
        long mostSignificantBits = (unixTimestampMillis << 16) | (0x7L << 12) | randomA;

        long leastSignificantBits = ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL;
        leastSignificantBits |= 0x8000000000000000L;

        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
