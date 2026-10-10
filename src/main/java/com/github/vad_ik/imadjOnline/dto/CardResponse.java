package com.github.vad_ik.imadjOnline.dto;

import java.time.Instant;

public record CardResponse(
        String id,
        String deckId,
        String imageUrl,
        Instant createdAt
) {
}
