package com.teamflow.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class BrowserHeaders extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/") || path.equals("/index.html") || path.startsWith("/assets/")) {
            response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
