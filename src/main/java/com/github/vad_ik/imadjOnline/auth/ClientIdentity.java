package com.github.vad_ik.imadjOnline.auth;

public record ClientIdentity(
        String clientId,
        Long userId,
        String displayName,
        String avatarUrl
) {
    public boolean registered() {
        return userId != null;
    }
}
