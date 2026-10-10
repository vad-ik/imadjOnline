package com.github.vad_ik.imadjOnline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record DeckResponse(
        String id,
        String name,
        @JsonProperty("isPublic") boolean isPublic,
        String ownerId,
        int cardCount,
        List<CardResponse> cards,
        Instant createdAt
) {
}
