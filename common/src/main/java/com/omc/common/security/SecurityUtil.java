package com.omc.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

public class SecurityUtil {

    private SecurityUtil() {
    }

    public static Optional<UUID> getCurrentUserId() {
        return getPrincipal().map(u -> {
            try {
                return UUID.fromString(u.getUserId());
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    public static Optional<String> getCurrentUsername() {
        return getPrincipal().map(CustomUserDetails::getUsername);
    }

    public static Optional<String> getCurrentUserRole() {
        return getPrincipal().map(CustomUserDetails::getRole);
    }

    private static Optional<CustomUserDetails> getPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return Optional.of(userDetails);
        }
        return Optional.empty();
    }
}
