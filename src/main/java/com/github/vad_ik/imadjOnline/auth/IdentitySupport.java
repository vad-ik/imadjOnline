package com.github.vad_ik.imadjOnline.auth;

import com.github.vad_ik.imadjOnline.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class IdentitySupport {

    public ClientIdentity current(HttpServletRequest request) {
        Object raw = request.getAttribute(AuthConstants.ATTR_IDENTITY);
        if (raw instanceof ClientIdentity identity) {
            return identity;
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "identity_missing", "Identity missing");
    }

    public ClientIdentity requireRegistered(HttpServletRequest request) {
        ClientIdentity identity = current(request);
        if (!identity.registered()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Login required");
        }
        return identity;
    }
}
