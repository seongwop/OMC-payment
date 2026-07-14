package com.omc.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.common.exception.CommonErrorCode;
import com.omc.common.response.ErrorResponse;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Message;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

// 게이트웨이가 JWT 검증 후 주입한 헤더를 읽어 SecurityContext에 세팅하는 필터.
public class GatewayHeaderAuthFilter extends OncePerRequestFilter {

    private final String gatewaySecret;

    public GatewayHeaderAuthFilter(String gatewaySecret) {
        this.gatewaySecret = gatewaySecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/actuator")
                || path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String requestSecret = request.getHeader("X-Gateway-Secret");

        if (!gatewaySecret.equals(requestSecret)) {
            SentryEvent event = new SentryEvent();
            event.setLevel(SentryLevel.WARNING);
            Message msg = new Message();
            msg.setMessage("게이트웨이 우회 감지: X-Gateway-Secret 불일치 [" + request.getMethod() + " " + request.getRequestURI() + "]");
            event.setMessage(msg);
            Sentry.captureEvent(event);

            ErrorResponse<Void> errorResponse = ErrorResponse.of(
                    HttpStatus.FORBIDDEN,
                    CommonErrorCode.ACCESS_DENIED.getCode(),
                    CommonErrorCode.ACCESS_DENIED.getMessage()
            );
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(new ObjectMapper().writeValueAsString(errorResponse));
            return;
        }

        String userId = request.getHeader("X-User-Id");
        String username = request.getHeader("X-Username");
        String userRole = request.getHeader("X-User-Role");

        if (userId != null && userRole != null) {
            SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + userRole);
            List<SimpleGrantedAuthority> authorities = Collections.singletonList(authority);

            CustomUserDetails userDetails = new CustomUserDetails(userId, username, userRole, authorities);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
