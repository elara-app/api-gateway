package com.elara.app.api_gateway.config;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Writes one line per proxied request: method, path, status and duration.
 *
 * <p>Actuator paths are skipped so container healthchecks do not flood the
 * log. Query strings, headers and bodies are deliberately not logged, to keep
 * the line small and avoid leaking credentials or payloads.
 *
 * <p>Level policy: 5xx is {@code ERROR}, 4xx and aborted exchanges are
 * {@code WARN}, everything else is {@code INFO}.
 */
@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String method = exchange.getRequest().getMethod().name();
        long start = System.nanoTime();

        return chain.filter(exchange).doFinally(signal -> {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            int code = status != null ? status.value() : 0;
            String statusText = status != null ? String.valueOf(code) : "n/a";

            if (code >= 500) {
                log.error("[access] {} {} -> {} ({} ms)", method, path, statusText, durationMs);
            } else if (code >= 400 || code == 0) {
                log.warn("[access] {} {} -> {} ({} ms)", method, path, statusText, durationMs);
            } else {
                log.info("[access] {} {} -> {} ({} ms)", method, path, statusText, durationMs);
            }
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
