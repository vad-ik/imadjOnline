package com.github.vad_ik.imadjOnline.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String path = request.getRequestURI();
        boolean interesting = path.startsWith("/api/") || path.startsWith("/ws/");
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (interesting) {
                log.info(
                        "{} {} -> {} ({} ms) ua={}",
                        request.getMethod(),
                        path + (request.getQueryString() == null ? "" : "?" + request.getQueryString()),
                        response.getStatus(),
                        System.currentTimeMillis() - start,
                        truncate(request.getHeader("User-Agent"), 80)
                );
            }
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "-";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
