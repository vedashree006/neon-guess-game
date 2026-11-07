package com.vedashree.game.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * Resolves the address used ONLY for in-memory rate limiting. It is never stored or logged.
 * X-Forwarded-For is honoured only when TRUST_PROXY=true, and then only its LAST entry (the
 * address appended by your own reverse proxy); earlier entries can be forged by the client.
 */
public final class ClientIp {
    private static final Pattern SAFE = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private ClientIp() { }

    public static String resolve(HttpServletRequest request, boolean trustProxy) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                String last = parts[parts.length - 1].trim();
                if (SAFE.matcher(last).matches()) {
                    return last;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
