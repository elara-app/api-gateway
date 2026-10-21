package com.elara.app.api_gateway.config;

import java.time.Duration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsSuccessfulRequestAtInfo() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/inventory/item/");

        filter.filter(exchange, chainWithStatus(HttpStatus.OK)).block();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
            .contains("GET", "/api/v1/inventory/item/", "200");
    }

    @Test
    void logsClientErrorAtWarn() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/uom/999");

        filter.filter(exchange, chainWithStatus(HttpStatus.NOT_FOUND)).block();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("404");
    }

    @Test
    void logsServerErrorAtError() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/uom/");

        filter.filter(exchange, chainWithStatus(HttpStatus.INTERNAL_SERVER_ERROR)).block();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("500");
    }

    @Test
    void skipsActuatorPaths() {
        MockServerWebExchange exchange = exchangeFor("/actuator/health");

        filter.filter(exchange, chainWithStatus(HttpStatus.OK)).block();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void logsPathsThatOnlyShareTheActuatorPrefix() {
        MockServerWebExchange exchange = exchangeFor("/actuator-admin");

        filter.filter(exchange, chainWithStatus(HttpStatus.OK)).block();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void logsUnhandledErrorAtErrorAs500() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/uom/");

        assertThatThrownBy(() -> filter
            .filter(exchange, e -> Mono.error(new IllegalStateException("boom")))
            .block())
            .isInstanceOf(IllegalStateException.class);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("500");
    }

    @Test
    void logsErrorResponseStatus() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/uom/999");

        assertThatThrownBy(() -> filter
            .filter(exchange, e -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing")))
            .block())
            .isInstanceOf(ResponseStatusException.class);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("404");
    }

    @Test
    void logsCancelledRequestAtWarn() {
        MockServerWebExchange exchange = exchangeFor("/api/v1/uom/");

        assertThatThrownBy(() -> filter.filter(exchange, e -> {
            e.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.never();
        }).block(Duration.ofMillis(50)))
            .isInstanceOf(IllegalStateException.class);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("200", "cancelled");
    }

    private static MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private static GatewayFilterChain chainWithStatus(HttpStatus status) {
        return exchange -> {
            exchange.getResponse().setStatusCode(status);
            return Mono.empty();
        };
    }
}
