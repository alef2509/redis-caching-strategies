package io.github.alef2509.catalog.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the token bucket per client (API key, or IP address) and exposes the
 * IETF {@code RateLimit-*} headers so well-behaved clients can pace themselves.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String apiKey = request.getHeader("X-Api-Key");
        String client = apiKey != null && !apiKey.isBlank() ? "key:" + apiKey : "ip:" + request.getRemoteAddr();
        RateLimiter.Decision decision = limiter.tryAcquire(client);

        response.setHeader("RateLimit-Limit", Integer.toString(limiter.capacity()));
        response.setHeader("RateLimit-Remaining", Long.toString(decision.remaining()));
        if (decision.allowed()) {
            return true;
        }
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"title":"Too Many Requests","status":429,"detail":"Rate limit exceeded, retry in %d s"}"""
                .formatted(retryAfterSeconds));
        return false;
    }
}
