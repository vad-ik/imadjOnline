package com.github.vad_ik.imadjOnline.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Prevent stale game.js / HTML after deploys (browsers were stuck on ?v=5). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class NoCacheStaticFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css")
                || "/".equals(path) || path.endsWith("/game") || path.endsWith("/profile")) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
        }
        filterChain.doFilter(request, response);
    }
}
