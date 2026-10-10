package com.github.vad_ik.imadjOnline.controller;

import com.github.vad_ik.imadjOnline.auth.ClientIdentity;
import com.github.vad_ik.imadjOnline.auth.IdentitySupport;
import com.github.vad_ik.imadjOnline.dto.CardResponse;
import com.github.vad_ik.imadjOnline.dto.CreateDeckRequest;
import com.github.vad_ik.imadjOnline.dto.DeckResponse;
import com.github.vad_ik.imadjOnline.dto.GameDtos.RotateDeckRequest;
import com.github.vad_ik.imadjOnline.dto.GameDtos.RotateDeckResponse;
import com.github.vad_ik.imadjOnline.dto.PageResponse;
import com.github.vad_ik.imadjOnline.exception.ApiException;
import com.github.vad_ik.imadjOnline.service.DeckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Decks", description = "Колоды и карты (включая нарезку через UI профиля).")
@RestController
@RequestMapping("/api/decks")
public class DeckController {

    private final DeckService deckService;
    private final IdentitySupport identitySupport;

    public DeckController(DeckService deckService, IdentitySupport identitySupport) {
        this.deckService = deckService;
        this.identitySupport = identitySupport;
    }

    @Operation(summary = "Видимые колоды (публичные + свои)")
    @GetMapping
    public List<DeckResponse> listDecks(HttpServletRequest request) {
        return deckService.listVisibleDecks(identitySupport.current(request));
    }

    @Operation(summary = "Мои колоды")
    @GetMapping("/mine")
    public List<DeckResponse> myDecks(HttpServletRequest request) {
        return deckService.listMyDecks(identitySupport.current(request));
    }

    @Operation(summary = "Публичные колоды", description = "Просмотр для всех. Редактировать может только владелец.")
    @GetMapping("/public")
    public List<DeckResponse> publicDecks() {
        return deckService.listPublicDecks();
    }

    @Operation(summary = "Создать колоду")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DeckResponse createDeck(@Valid @RequestBody CreateDeckRequest request, HttpServletRequest httpRequest) {
        return deckService.createDeck(request, identitySupport.current(httpRequest));
    }

    @Operation(summary = "Колода с картами")
    @GetMapping("/{id}")
    public DeckResponse getDeck(@PathVariable String id, HttpServletRequest request) {
        return deckService.getDeckResponse(parseId(id, "Deck not found"), identitySupport.current(request));
    }

    @Operation(summary = "Карты колоды (пагинация)")
    @GetMapping("/{id}/cards")
    public PageResponse<CardResponse> listCards(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            HttpServletRequest request
    ) {
        return deckService.listCardsPaged(
                parseId(id, "Deck not found"),
                identitySupport.current(request),
                page,
                size
        );
    }

    @Operation(summary = "Удалить колоду и файлы в MinIO")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteDeck(@PathVariable String id, HttpServletRequest request) {
        deckService.deleteDeck(parseId(id, "Deck not found"), identitySupport.current(request));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Загрузить карту (картинка)")
    @PostMapping(path = "/{id}/cards", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse uploadCard(
            @PathVariable String id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request
    ) {
        return deckService.uploadCard(parseId(id, "Deck not found"), file, identitySupport.current(request));
    }

    @Operation(summary = "Удалить карту")
    @DeleteMapping("/{id}/cards/{cid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteCard(
            @PathVariable String id,
            @PathVariable String cid,
            HttpServletRequest request
    ) {
        ClientIdentity identity = identitySupport.current(request);
        deckService.deleteCard(
                parseId(id, "Deck or card not found"),
                parseId(cid, "Deck or card not found"),
                identity
        );
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Повернуть все карты колоды", description = "По умолчанию 180°. Пишет новые ключи в MinIO.")
    @PostMapping("/{id}/rotate")
    public RotateDeckResponse rotateDeck(
            @PathVariable String id,
            @RequestParam(required = false) Integer degrees,
            @RequestBody(required = false) RotateDeckRequest body,
            HttpServletRequest request
    ) {
        int deg = degrees != null
                ? degrees
                : (body != null && body.degrees() != null ? body.degrees() : 180);
        int rotated = deckService.rotateAllCards(
                parseId(id, "Deck not found"),
                deg,
                identitySupport.current(request)
        );
        return new RotateDeckResponse(rotated, deg);
    }

    private static long parseId(String raw, String notFoundMessage) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", notFoundMessage);
        }
    }
}
