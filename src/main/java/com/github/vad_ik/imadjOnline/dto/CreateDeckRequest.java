package com.github.vad_ik.imadjOnline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDeckRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        @JsonProperty("isPublic")
        Boolean isPublic
) {
    public boolean publicDeck() {
        return isPublic == null || isPublic;
    }
}
