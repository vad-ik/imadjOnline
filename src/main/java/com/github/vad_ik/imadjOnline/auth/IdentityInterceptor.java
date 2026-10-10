package com.github.vad_ik.imadjOnline.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class IdentityInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdentityInterceptor.class);

    private final ClientIdentityResolver resolver;

    public IdentityInterceptor(ClientIdentityResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ClientIdentity identity = resolver.resolve(request, response);
        request.setAttribute(AuthConstants.ATTR_IDENTITY, identity);
        if (request.getRequestURI().startsWith("/api/games") || request.getRequestURI().startsWith("/api/auth")) {
            log.info("identity {} {} clientId={} userId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    identity.clientId(),
                    identity.userId());
        }
        return true;
    }
}
