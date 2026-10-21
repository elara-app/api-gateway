package com.elara.app.api_gateway.config;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Writes one line per proxied request: method, path, status and duration.
 *
 * <p>Actuator paths are skipped so container healthchecks do not flood the
 * log. Query strings, headers and bodies are deliberately not logged, to keep
 * the line small and avoid leaking credentials or payloads.
 *
 * <p>Level policy: 5xx is always {@code ERROR}, cancellations included; 4xx
 * is {@code WARN}. Cancellations and errors after the response is committed
 * are {@code WARN}. Errors before commit derive their status from the error
 * ({@link ErrorResponse} or 500). Completions that never set a status are
 * {@code INFO}, as is everything else.
 */
@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (path.equals("/actuator") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }

        String method = exchange.getRequest().getMethod().name();
        long start = System.nanoTime();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        return chain.filter(exchange)
            .doOnError(failure::set)
            .doFinally(signal -> {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                int code = resolveStatusCode(exchange, failure.get());
                String statusText = code == 0 ? "n/a" : String.valueOf(code);
                String marker = switch (signal) {
                    case CANCEL -> ", cancelled";
                    case ON_ERROR -> ", failed";
                    default -> "";
                };

                if (code >= 500) {
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
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        boolean committed = exchange.getResponse().isCommitted();

        if (!committed && error instanceof ErrorResponse errorResponse) {
            return errorResponse.getStatusCode().value();
        }
        if (status != null && (committed || status.value() >= 500)) {
            return status.value();
        }
        if (error != null) {
            return 500;
        }
        return status != null ? status.value() : 0;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
