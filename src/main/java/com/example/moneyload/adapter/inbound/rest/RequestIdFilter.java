package com.example.moneyload.adapter.inbound.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = Collections.list(request.getHeaders(HEADER));
        String id = values.size() == 1 && values.getFirst().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                ? values.getFirst() : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        String previous = MDC.get("request_id");
        MDC.put("request_id", id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove("request_id");
            } else {
                MDC.put("request_id", previous);
            }
        }
    }
}
