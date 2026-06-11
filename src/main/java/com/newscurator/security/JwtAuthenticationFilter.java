package com.newscurator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.domain.enums.AccountRole;
import com.newscurator.exception.ErrorResponse;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ME_PATH = "/api/v1/me";
    private static final String EMAIL_VERIFY_PATH = "/api/v1/auth/email-verification";

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && jwtTokenProvider.validateToken(token)) {
            Claims claims = jwtTokenProvider.parseToken(token);
            UUID accountId = UUID.fromString(claims.getSubject());
            AccountRole role = AccountRole.valueOf(claims.get("role", String.class));
            boolean emailVerified = Boolean.TRUE.equals(claims.get("emailVerified", Boolean.class));

            CustomUserDetails userDetails = new CustomUserDetails(accountId, role, emailVerified);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            // emailVerified gating: /api/v1/me/** requires emailVerified=true
            // /api/v1/auth/email-verification/** is exempt (emailVerified=false allowed)
            String path = request.getServletPath();
            if (!emailVerified
                    && path.startsWith(ME_PATH)
                    && !path.startsWith(EMAIL_VERIFY_PATH)) {
                SecurityContextHolder.clearContext();
                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                        "EMAIL_NOT_VERIFIED", "Email verification required");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void writeError(HttpServletResponse response, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse error = ErrorResponse.of(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
