package com.relay.web;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.relay.config.RelayProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(2)
public class AuthFilter extends OncePerRequestFilter {

    private final RelayProperties properties;

    public AuthFilter(RelayProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.hasAuthToken() || "/".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (!properties.tokenMatches(request.getHeader("X-Auth-Token"))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"bad token\",\"status\":401}");
            return;
        }
        chain.doFilter(request, response);
    }
}
