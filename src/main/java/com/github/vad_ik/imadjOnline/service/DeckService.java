package com.github.vad_ik.imadjOnline.service;

import com.github.vad_ik.imadjOnline.auth.ClientIdentity;
import com.github.vad_ik.imadjOnline.db.entity.Card;
import com.github.vad_ik.imadjOnline.db.entity.Deck;
import com.github.vad_ik.imadjOnline.db.repository.CardRepository;
import com.github.vad_ik.imadjOnline.db.repository.DeckRepository;
import com.github.vad_ik.imadjOnline.dto.CardResponse;
import com.github.vad_ik.imadjOnline.dto.CreateDeckRequest;
import com.github.vad_ik.imadjOnline.dto.DeckResponse;
import com.github.vad_ik.imadjOnline.dto.PageResponse;
import com.github.vad_ik.imadjOnline.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class DeckService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeckService.class);

    private final DeckRepository deckRepository;
    private final CardRepository cardRepository;
    private final ImageStorageService imageStorageService;
    private final int maxCardsPerDeck;

    public DeckService(
            DeckRepository deckRepository,
            CardRepository cardRepository,
            ImageStorageService imageStorageService,
            @Value("${deck.max-cards:200}") int maxCardsPerDeck
    ) {
        this.deckRepository = deckRepository;
        this.cardRepository = cardRepository;
        this.imageStorageService = imageStorageService;
        this.maxCardsPerDeck = maxCardsPerDeck;
    }

    /** Used by Telegram bot — creates a public deck without owner. */
    @Transactional
    public Deck createDeck(String name) {
        return createDeckEntity(name, true, null);
    }

    @Transactional
    public DeckResponse createDeck(CreateDeckRequest request, ClientIdentity identity) {
        if (!identity.registered()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Login required to create decks");
        }
        boolean isPublic = request.publicDeck();
        Deck deck = createDeckEntity(request.name().trim(), isPublic, identity.userId());
        return toDeckResponse(deck, List.of());
    }

    private Deck createDeckEntity(String name, boolean isPublic, Long ownerId) {
        Deck deck = new Deck();
        deck.setName(name);
        deck.setPublic(isPublic);
        deck.setOwnerId(ownerId);
        return deckRepository.save(deck);
    }

    public List<Deck> listDecks() {
        return deckRepository.findAllByOrderByIdAsc();
    }

    public List<DeckResponse> listVisibleDecks(ClientIdentity identity) {
        List<Deck> decks = identity.registered()
                ? deckRepository.findVisibleToUser(identity.userId())
                : deckRepository.findByIsPublicTrueOrderByIdAsc();
        return decks.stream().map(deck -> toDeckResponse(deck, List.of())).toList();
    }

    public List<DeckResponse> listMyDecks(ClientIdentity identity) {
        if (!identity.registered()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Login required");
        }
        return deckRepository.findByOwnerIdOrderByIdAsc(identity.userId()).stream()
                .map(deck -> toDeckResponse(deck, List.of()))
                .toList();
    }

    /** Public decks only — for browse UI (guests and registered). */
    public List<DeckResponse> listPublicDecks() {
        return deckRepository.findByIsPublicTrueOrderByIdAsc().stream()
                .map(deck -> toDeckResponse(deck, List.of()))
                .toList();
    }

    public Deck requireDeck(Long deckId) {
        return deckRepository.findById(deckId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "not_found",
                        "Deck not found"
                ));
    }

    public DeckResponse getDeckResponse(Long deckId, ClientIdentity identity) {
        Deck deck = requireDeck(deckId);
        ensureCanView(deck, identity);
        List<CardResponse> cards = cardRepository.findByDeckIdOrderByIdAsc(deckId).stream()
                .map(this::toCardResponse)
                .toList();
        return toDeckResponse(deck, cards);
    }

    public PageResponse<CardResponse> listCardsPaged(Long deckId, ClientIdentity identity, int page, int size) {
        Deck deck = requireDeck(deckId);
        ensureCanView(deck, identity);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 60);
        Page<Card> result = cardRepository.findByDeckIdOrderByIdAsc(deckId, PageRequest.of(safePage, safeSize));
        List<CardResponse> items = result.getContent().stream().map(this::toCardResponse).toList();
        return new PageResponse<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public void deleteDeck(Long deckId, ClientIdentity identity) {
        Deck deck = requireDeck(deckId);
        ensureCanEdit(deck, identity);
        List<Card> cards = cardRepository.findByDeckIdOrderByIdAsc(deckId);
        for (Card card : cards) {
            imageStorageService.delete(card.getImageKey());
        }
        deckRepository.delete(deck);
    }

    /** Used by Telegram bot. */
    @Transactional
    public Card addCard(Long deckId, byte[] imageBytes, String contentType, String filename, String code) {
        requireDeck(deckId);
        ensureDeckNotFull(deckId);

        String imageKey;
        try {
            imageKey = imageStorageService.upload(imageBytes, contentType, filename);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_file", "File is corrupted");
        }

        Card card = new Card();
        card.setDeckId(deckId);
        card.setImageKey(imageKey);
        if (code != null && !code.isBlank()) {
            card.setCode(code.trim());
        }
        return cardRepository.save(card);
    }

    @Transactional
    public CardResponse uploadCard(Long deckId, MultipartFile file, ClientIdentity identity) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_missing", "File missing");
        }

        Deck deck = requireDeck(deckId);
        ensureCanEdit(deck, identity);
        ensureDeckNotFull(deckId);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_file", "File is corrupted");
        }

        if (bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_missing", "File missing");
        }

        String contentType = file.getContentType();
        if (contentType != null && !contentType.startsWith("image/")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_file", "File is corrupted");
        }

        Card card = addCard(deckId, bytes, contentType, file.getOriginalFilename(), null);
        return toCardResponse(card);
    }

    @Transactional
    public void deleteCard(Long deckId, Long cardId, ClientIdentity identity) {
        Deck deck = requireDeck(deckId);
        ensureCanEdit(deck, identity);
        Card card = cardRepository.findByIdAndDeckId(cardId, deckId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "not_found",
                        "Deck or card not found"
                ));
        imageStorageService.delete(card.getImageKey());
        cardRepository.delete(card);
    }

    /**
     * Rotate every card image in the deck (default 180°). Writes a new object key
     * so browser caches of /api/images/content?key=… are busted.
     */
    @Transactional
    public int rotateAllCards(Long deckId, int degrees, ClientIdentity identity) {
        Deck deck = requireDeck(deckId);
        ensureCanEdit(deck, identity);
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized != 90 && normalized != 180 && normalized != 270) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_degrees", "Use 90, 180 or 270");
        }
        List<Card> cards = cardRepository.findByDeckIdOrderByIdAsc(deckId);
        int done = 0;
        for (Card card : cards) {
            String oldKey = card.getImageKey();
            try {
                byte[] original = imageStorageService.downloadBytes(oldKey);
                byte[] rotated = rotateImageBytes(original, normalized);
                String newKey = imageStorageService.upload(rotated, "image/jpeg", "rot.jpg");
                card.setImageKey(newKey);
                cardRepository.save(card);
                imageStorageService.delete(oldKey);
                done++;
            } catch (Exception e) {
                log.warn("rotate failed card={} key={}: {}", card.getId(), oldKey, e.toString());
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "rotate_failed",
                        "Cannot rotate card " + card.getId() + ": " + e.getMessage()
                );
            }
        }
        return done;
    }

    private static byte[] rotateImageBytes(byte[] input, int degrees) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(input));
        if (src == null) {
            throw new IOException("Unsupported image format");
        }
        // Flatten to RGB so JPEG writer always accepts the result.
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g0 = rgb.createGraphics();
        g0.drawImage(src, 0, 0, java.awt.Color.WHITE, null);
        g0.dispose();

        int w = rgb.getWidth();
        int h = rgb.getHeight();
        int outW = (degrees == 90 || degrees == 270) ? h : w;
        int outH = (degrees == 90 || degrees == 270) ? w : h;
        BufferedImage dst = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, outW, outH);
        AffineTransform at = new AffineTransform();
        switch (degrees) {
            case 90 -> {
                at.translate(outW, 0);
                at.rotate(Math.toRadians(90));
            }
            case 180 -> {
                at.translate(outW, outH);
                at.rotate(Math.toRadians(180));
            }
            case 270 -> {
                at.translate(0, outH);
                at.rotate(Math.toRadians(270));
            }
            default -> {
            }
        }
        g.drawImage(rgb, at, null);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(dst, "jpg", baos)) {
            throw new IOException("JPEG writer failed");
        }
        return baos.toByteArray();
    }

    public List<Card> listCards(Long deckId) {
        requireDeck(deckId);
        return cardRepository.findByDeckIdOrderByIdAsc(deckId);
    }

    private void ensureCanView(Deck deck, ClientIdentity identity) {
        if (deck.isPublic()) {
            return;
        }
        if (identity.registered() && identity.userId().equals(deck.getOwnerId())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "private_deck", "Private deck");
    }

    private void ensureCanEdit(Deck deck, ClientIdentity identity) {
        if (!identity.registered()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Login required");
        }
        if (deck.getOwnerId() == null || !deck.getOwnerId().equals(identity.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Not your deck");
        }
    }

    private void ensureDeckNotFull(Long deckId) {
        if (cardRepository.countByDeckId(deckId) >= maxCardsPerDeck) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "deck_full", "Deck is full");
        }
    }

    private DeckResponse toDeckResponse(Deck deck, List<CardResponse> cards) {
        return new DeckResponse(
                String.valueOf(deck.getId()),
                deck.getName(),
                deck.isPublic(),
                deck.getOwnerId() == null ? null : String.valueOf(deck.getOwnerId()),
                (int) cardRepository.countByDeckId(deck.getId()),
                cards,
                deck.getCreatedAt()
        );
    }

    private CardResponse toCardResponse(Card card) {
        return new CardResponse(
                String.valueOf(card.getId()),
                String.valueOf(card.getDeckId()),
                imageStorageService.presignedUrl(card.getImageKey()),
                card.getCreatedAt()
        );
    }
}
