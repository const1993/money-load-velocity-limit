package com.example.moneyload.adapter.inbound.rest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.moneyload.adapter.inbound.file.FileLoadProcessingException;
import com.example.moneyload.application.error.TechnicalFailureClassifier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import static org.assertj.core.api.Assertions.*;

class FileErrorLoggingTests {
    @Test
    void logsLineCauseTypesAndStackLocationsWithoutSensitiveMessages() {
        var logger = (Logger) LoggerFactory.getLogger(LoadExceptionHandler.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var failure = new FileLoadProcessingException(42,
                    new TransientDataAccessResourceException("SECRET SQL PAYLOAD", new IllegalStateException("SECRET CAUSE")));
            var response = new LoadExceptionHandler(new TechnicalFailureClassifier()).failedFile(failure,
                    new ServletWebRequest(new MockHttpServletRequest()));
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(events.list).hasSize(1);
            var event = events.list.getFirst();
            assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("line_number");
                assertThat(pair.value).isEqualTo(42L);
            });
            String fields = event.getKeyValuePairs().toString();
            assertThat(fields).contains("TransientDataAccessResourceException", "FileErrorLoggingTests", "IllegalStateException")
                    .doesNotContain("SECRET");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
