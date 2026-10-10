package com.github.vad_ik.imadjOnline.service;

import com.github.vad_ik.imadjOnline.auth.AuthConstants;
import com.github.vad_ik.imadjOnline.auth.ClientIdentity;
import com.github.vad_ik.imadjOnline.auth.RememberMeService;
import com.github.vad_ik.imadjOnline.db.entity.UserAccount;
import com.github.vad_ik.imadjOnline.db.repository.UserRepository;
import com.github.vad_ik.imadjOnline.dto.AuthDtos.LoginRequest;
import com.github.vad_ik.imadjOnline.dto.AuthDtos.RegisterRequest;
import com.github.vad_ik.imadjOnline.dto.AuthDtos.UpdateProfileRequest;
import com.github.vad_ik.imadjOnline.dto.AuthDtos.UserResponse;
import com.github.vad_ik.imadjOnline.exception.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ImageStorageService imageStorageService;
    private final RememberMeService rememberMeService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            ImageStorageService imageStorageService,
            RememberMeService rememberMeService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.imageStorageService = imageStorageService;
        this.rememberMeService = rememberMeService;
    }

    @Transactional
    public UserResponse register(
            RegisterRequest request,
            String clientId,
            HttpSession session,
            HttpServletResponse response
    ) {
        String username = request.username().trim();
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "username_taken", "Username already taken");
        }
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user = userRepository.save(user);
        session.setAttribute(AuthConstants.SESSION_USER_ID, user.getId());
        rememberMeService.issue(response, user.getId());
        return toResponse(user, "u:" + user.getId());
    }

    public UserResponse login(
            LoginRequest request,
            String clientId,
            HttpSession session,
            HttpServletResponse response
    ) {
        UserAccount user = userRepository.findByUsernameIgnoreCase(request.username().trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Invalid login"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Invalid login");
        }
        session.setAttribute(AuthConstants.SESSION_USER_ID, user.getId());
        rememberMeService.issue(response, user.getId());
        return toResponse(user, "u:" + user.getId());
    }

    public void logout(HttpSession session, HttpServletResponse response) {
        rememberMeService.clear(response);
        if (session != null) {
            session.invalidate();
        }
    }

    public UserResponse me(ClientIdentity identity) {
        if (!identity.registered()) {
            return new UserResponse(null, null, identity.displayName(), identity.avatarUrl(), identity.clientId());
        }
        UserAccount user = requireUser(identity.userId());
        return toResponse(user, identity.clientId());
    }

    @Transactional
    public UserResponse updateProfile(Long userId, String clientId, UpdateProfileRequest request) {
        UserAccount user = requireUser(userId);
        if (request.displayName() != null && !request.displayName().isBlank()) {
            user.setDisplayName(request.displayName().trim());
        }
        return toResponse(userRepository.save(user), clientId);
    }

    @Transactional
    public UserResponse updateAvatar(Long userId, String clientId, MultipartFile file) {
        UserAccount user = requireUser(userId);
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_missing", "File missing");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.startsWith("image/")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_file", "File must be an image");
        }
        try {
            if (user.getAvatarKey() != null) {
                imageStorageService.delete(user.getAvatarKey());
            }
            String key = imageStorageService.uploadAvatar(file.getBytes(), contentType, file.getOriginalFilename());
            user.setAvatarKey(key);
            return toResponse(userRepository.save(user), clientId);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_file", "File is corrupted");
        }
    }

    public UserAccount requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Login required"));
    }

    public UserResponse toResponse(UserAccount user, String clientId) {
        String avatarUrl = user.getAvatarKey() == null ? null : imageStorageService.presignedUrl(user.getAvatarKey());
        return new UserResponse(
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getDisplayName(),
                avatarUrl,
                clientId
        );
    }
}
