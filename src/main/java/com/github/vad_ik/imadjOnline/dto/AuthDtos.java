package com.github.vad_ik.imadjOnline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 6, max = 100) String password,
            @NotBlank @Size(min = 1, max = 64) String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }

    public record UpdateProfileRequest(
            @Size(min = 1, max = 64) String displayName
    ) {
    }

    public record UserResponse(
            String id,
            String username,
            String displayName,
            String avatarUrl,
            String clientId
    ) {
    }
}
