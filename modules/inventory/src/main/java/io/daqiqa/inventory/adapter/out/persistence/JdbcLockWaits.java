package io.daqiqa.inventory.adapter.out.persistence;

import io.daqiqa.inventory.application.LockWaits;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLockWaits implements LockWaits {

    private final JdbcClient jdbc;

    JdbcLockWaits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void limitTo(Duration timeout) {
        // set_config(..., is_local => true) is SET LOCAL: it ends with the transaction, so a pooled connection never
        // carries this timeout into whatever runs on it next.
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout.toMillis() + "ms")
                .query()
                .singleValue();
    }
}
