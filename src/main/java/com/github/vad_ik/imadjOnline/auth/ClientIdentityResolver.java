package com.github.vad_ik.imadjOnline.auth;

import com.github.vad_ik.imadjOnline.db.entity.UserAccount;
import com.github.vad_ik.imadjOnline.db.repository.UserRepository;
import com.github.vad_ik.imadjOnline.service.ImageStorageService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ClientIdentityResolver {

    private final UserRepository userRepository;
    private final ImageStorageService imageStorageService;
    private final RememberMeService rememberMeService;

    public ClientIdentityResolver(
            UserRepository userRepository,
            ImageStorageService imageStorageService,
            RememberMeService rememberMeService
    ) {
        this.userRepository = userRepository;
        this.imageStorageService = imageStorageService;
        this.rememberMeService = rememberMeService;
    }

    public ClientIdentity resolve(HttpServletRequest request, HttpServletResponse response) {
        String clientId = readCookie(request, AuthConstants.CLIENT_ID_COOKIE);
        if (clientId == null || clientId.isBlank()) {
            clientId = UUID.randomUUID().toString();
            Cookie cookie = new Cookie(AuthConstants.CLIENT_ID_COOKIE, clientId);
            cookie.setPath("/");
            cookie.setHttpOnly(false);
            cookie.setMaxAge(60 * 60 * 24 * 365);
            response.addCookie(cookie);
        }

        Long userId = readSessionUserId(request);
        if (userId == null) {
            userId = rememberMeService.readUserId(request);
            if (userId != null) {
                // Restore in-memory session after rebuild / expired JVM session.
                HttpSession session = request.getSession(true);
                session.setAttribute(AuthConstants.SESSION_USER_ID, userId);
            }
        }

        if (userId != null) {
            UserAccount user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                String avatarUrl = user.getAvatarKey() == null
                        ? null
                        : imageStorageService.presignedUrl(user.getAvatarKey());
                clientId = "u:" + user.getId();
                return new ClientIdentity(clientId, user.getId(), user.getDisplayName(), avatarUrl);
            }
            // Stale cookie/session pointing at deleted user.
            rememberMeService.clear(response);
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }

        String guestName = "Гость-" + clientId.substring(0, Math.min(4, clientId.length())).toUpperCase();
        return new ClientIdentity(clientId, null, guestName, null);
    }

    private static Long readSessionUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object raw = session.getAttribute(AuthConstants.SESSION_USER_ID);
        if (raw instanceof Long id) {
            return id;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return null;
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
