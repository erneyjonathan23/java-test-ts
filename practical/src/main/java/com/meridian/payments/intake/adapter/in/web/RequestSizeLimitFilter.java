package com.meridian.payments.intake.adapter.in.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.meridian.payments.intake.config.IntakeProperties;

/**
 * Rejects webhook requests whose declared {@code Content-Length} exceeds the limit before the body is read,
 * so an oversized request cannot make the service buffer it in memory (defence in depth: the API Manager
 * enforces the same limit at the edge).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public RequestSizeLimitFilter(IntakeProperties properties) {
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"status\":413,\"title\":\"Payload too large\",\"correlationId\":\""
                    + request.getAttribute(CorrelationIdFilter.ATTRIBUTE) + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
