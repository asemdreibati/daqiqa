package io.daqiqa.kernel;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;

/** Identifier generation. */
public final class Ids {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private Ids() {}

    /**
     * Returns a new RFC 9562 version 7 UUID.
     *
     * <p>Version 7 UUIDs start with a millisecond timestamp, so new primary keys are appended to the right of B-tree
     * indexes instead of landing on random pages (as UUIDv4 would), which keeps inserts fast and indexes compact.
     */
    public static UUID newTimeOrderedId() {
        return GENERATOR.generate();
    }
}
