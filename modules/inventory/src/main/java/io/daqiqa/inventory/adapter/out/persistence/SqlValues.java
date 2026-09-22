package io.daqiqa.inventory.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Conversions between Java values and what the PostgreSQL driver binds and reads. */
final class SqlValues {

    private SqlValues() {}

    /** The driver binds {@code OffsetDateTime} (not {@code Instant}) to {@code timestamptz}. */
    static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new IllegalStateException("Column " + column + " is null");
        }
        return value.toInstant();
    }
}
