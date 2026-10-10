package com.github.vad_ik.imadjOnline.controller;

import com.github.vad_ik.imadjOnline.auth.ClientIdentity;
import com.github.vad_ik.imadjOnline.auth.IdentitySupport;
import com.github.vad_ik.imadjOnline.dto.AuthDtos.LoginRequest;
import com.github.vad_ik.imadjOnline.dto.AuthDtos.RegisterRequest;
import com.github.vad_ik.imadjOnline.dto.AuthDtos.UpdateProfileRequest;
import com.github.vad_ik.imadjOnline.dto.AuthDtos.UserResponse;
import com.github.vad_ik.imadjOnline.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Auth", description = "Регистрация, вход, профиль. После login/register ставится cookie rememberMe.")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final IdentitySupport identitySupport;

    public AuthController(AuthService authService, IdentitySupport identitySupport) {
        this.authService = authService;
        this.identitySupport = identitySupport;
    }

    @Operation(summary = "Регистрация", description = "Создаёт пользователя, открывает сессию и rememberMe cookie.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        ClientIdentity identity = identitySupport.current(httpRequest);
        return authService.register(request, identity.clientId(), httpRequest.getSession(true), httpResponse);
    }

    @Operation(summary = "Вход")
    @PostMapping("/login")
    public UserResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        ClientIdentity identity = identitySupport.current(httpRequest);
        return authService.login(request, identity.clientId(), httpRequest.getSession(true), httpResponse);
    }

    @Operation(summary = "Выход", description = "Инвалидирует сессию и очищает rememberMe.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpSession session, HttpServletResponse response) {
        authService.logout(session, response);
    }

    @Operation(summary = "Текущий пользователь", description = "Гость без id или зарегистрированный аккаунт.")
    @GetMapping("/me")
    public UserResponse me(HttpServletRequest request) {
        return authService.me(identitySupport.current(request));
    }

    @Operation(summary = "Обновить ник")
    @PutMapping("/profile")
    public UserResponse updateProfile(
            @Valid @RequestBody UpdateProfileRequest body,
            HttpServletRequest request
    ) {
        ClientIdentity identity = identitySupport.requireRegistered(request);
        return authService.updateProfile(identity.userId(), identity.clientId(), body);
    }

    @Operation(summary = "Загрузить аватар")
    @PostMapping(path = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserResponse updateAvatar(
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request
    ) {
        ClientIdentity identity = identitySupport.requireRegistered(request);
        return authService.updateAvatar(identity.userId(), identity.clientId(), file);
    }
}
