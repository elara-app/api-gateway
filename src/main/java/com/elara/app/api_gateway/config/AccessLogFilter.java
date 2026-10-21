package com.elara.app.api_gateway.config;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.DisconnectedClientHelper;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Writes one line per request: method, path, status and duration.
 *
 * <p>Runs as a {@link WebFilter} so requests that never match a route (for
 * example a gateway 404) are logged too. Actuator paths are skipped so
 * container healthchecks do not flood the log. Query strings, headers and
 * bodies are deliberately not logged, to keep the line small and avoid
 * leaking credentials or payloads.
 *
 * <p>Level policy: 5xx is always {@code ERROR}, cancellations included; 4xx
 * is {@code WARN}. Client disconnects, cancellations and errors after the
 * response is committed are {@code WARN}. Errors before commit derive their
 * status from the error ({@link ErrorResponse} or 500), even if a stale status
 * was already set. Completions that never set a status are {@code INFO}, as is
 * everything else.
 */
@Component
public class AccessLogFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = sanitizePath(exchange.getRequest().getURI().getRawPath());
        if (path.equals("/actuator") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }

        HttpMethod methodValue = exchange.getRequest().getMethod();
        String method = sanitize(methodValue != null ? methodValue.name() : "UNKNOWN");
        long start = System.nanoTime();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        return chain.filter(exchange)
            .doOnError(failure::set)
            .doFinally(signal -> {
                Throwable error = failure.get();
                boolean clientGone = DisconnectedClientHelper.isClientDisconnectedException(error);
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                int code = clientGone ? 0 : resolveStatusCode(exchange, error);
                String statusText = code == 0 ? "n/a" : String.valueOf(code);
                String marker = clientGone || signal == SignalType.CANCEL ? ", cancelled"
                    : signal == SignalType.ON_ERROR ? ", failed"
                    : "";

                if (clientGone) {
                    log.warn("[access] {} {} -> n/a ({} ms{})", method, path, durationMs, marker);
                } else if (code >= 500) {
                    log.error("[access] {} {} -> {} ({} ms{})", method, path, statusText, durationMs, marker);
                } else if (code == 0 && signal == SignalType.ON_COMPLETE) {
                    log.info("[access] {} {} -> n/a ({} ms)", method, path, durationMs);
                } else if (signal == SignalType.CANCEL || signal == SignalType.ON_ERROR
                    || code >= 400 || code == 0) {
                    log.warn("[access] {} {} -> {} ({} ms{})", method, path, statusText, durationMs, marker);
                } else {
                    log.info("[access] {} {} -> {} ({} ms)", method, path, statusText, durationMs);
                }
            });
    }

    private static int resolveStatusCode(ServerWebExchange exchange, Throwable error) {
        boolean committed = exchange.getResponse().isCommitted();
        HttpStatusCode status = exchange.getResponse().getStatusCode();

        if (!committed && error instanceof ErrorResponse errorResponse) {
            return errorResponse.getStatusCode().value();
        }
        if (status != null && committed) {
            return status.value();
        }
        if (error != null) {
            return 500;
        }
        return status != null ? status.value() : 0;
    }

    private static String sanitizePath(String rawPath) {
        return sanitize(rawPath == null || rawPath.isEmpty() ? "/" : rawPath);
    }

    private static String sanitize(String value) {
        return value.replaceAll("\\p{Cntrl}", "?");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
