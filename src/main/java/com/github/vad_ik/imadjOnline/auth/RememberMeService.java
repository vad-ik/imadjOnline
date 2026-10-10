package com.github.vad_ik.imadjOnline.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Long-lived signed cookie so login survives F5 and Docker rebuilds
 * (in-memory HttpSession alone does not).
 */
@Component
public class RememberMeService {

    public static final Duration COOKIE_TTL = Duration.ofDays(365);

    private final byte[] secret;

    public RememberMeService(
            @Value("${auth.remember-me-secret:${MINIO_ROOT_PASSWORD:imadj-dev-secret}}") String secret
    ) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void issue(HttpServletResponse response, long userId) {
        long exp = Instant.now().getEpochSecond() + COOKIE_TTL.toSeconds();
        String payload = userId + "." + exp;
        String token = payload + "." + sign(payload);
        writeCookie(response, token, COOKIE_TTL);
    }

    public void clear(HttpServletResponse response) {
        writeCookie(response, "", Duration.ZERO);
    }

    public Long readUserId(HttpServletRequest request) {
        String raw = readCookie(request, AuthConstants.REMEMBER_ME_COOKIE);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String payload = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8)
        )) {
            return null;
        }
        try {
            long userId = Long.parseLong(parts[0]);
            long exp = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() > exp) {
                return null;
            }
            return userId;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(AuthConstants.REMEMBER_ME_COOKIE, value)
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign remember-me cookie", e);
        }
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
