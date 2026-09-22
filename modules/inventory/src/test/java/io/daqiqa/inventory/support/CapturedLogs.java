package io.daqiqa.inventory.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Collects what a class logs while a test runs, so the test can assert on levels as an operator would see them. */
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private CapturedLogs(Logger logger) {
        this.logger = logger;
    }

    public static CapturedLogs of(Class<?> type) {
        CapturedLogs logs = new CapturedLogs((Logger) LoggerFactory.getLogger(type));
        logs.appender.start();
        logs.logger.addAppender(logs.appender);
        return logs;
    }

    public List<String> messagesAt(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
