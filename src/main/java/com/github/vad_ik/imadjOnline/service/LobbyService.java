package com.github.vad_ik.imadjOnline.service;

import com.github.vad_ik.imadjOnline.auth.ClientIdentity;
import com.github.vad_ik.imadjOnline.db.entity.Card;
import com.github.vad_ik.imadjOnline.db.entity.Deck;
import com.github.vad_ik.imadjOnline.db.repository.CardRepository;
import com.github.vad_ik.imadjOnline.db.repository.DeckRepository;
import com.github.vad_ik.imadjOnline.dto.GameDtos.GameStateView;
import com.github.vad_ik.imadjOnline.dto.GameDtos.HandCard;
import com.github.vad_ik.imadjOnline.dto.GameDtos.LobbySummary;
import com.github.vad_ik.imadjOnline.dto.GameDtos.PlayerView;
import com.github.vad_ik.imadjOnline.dto.GameDtos.TableCardView;
import com.github.vad_ik.imadjOnline.exception.ApiException;
import com.github.vad_ik.imadjOnline.model.Game;
import com.github.vad_ik.imadjOnline.model.Game.TableCard;
import com.github.vad_ik.imadjOnline.model.GameStatus;
import com.github.vad_ik.imadjOnline.model.Player;
import com.github.vad_ik.imadjOnline.model.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class LobbyService {

    private static final Logger log = LoggerFactory.getLogger(LobbyService.class);

    private static final String SCORING_HINT = """
            Все угадали ведущего: ведущий 0, остальные по 2.
            Никто не угадал: +1 за каждую фишку на твоей карте (на Кисоньку ×3).
            Не все: ведущий и угадавшие по 3; +1 за фишку на чужой (не ведущего) карте (на Кисоньку ×3).
            """.trim();

    /** Grace period so a page refresh does not drop you from a waiting lobby. */
    private static final long LOBBY_LEAVE_GRACE_MS = 4_000;

    private final ConcurrentHashMap<String, Game> games = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingLobbyRemovals = new ConcurrentHashMap<>();
    private final ScheduledExecutorService leaveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lobby-leave");
        t.setDaemon(true);
        return t;
    });
    private final DeckRepository deckRepository;
    private final CardRepository cardRepository;
    private final ImageStorageService imageStorageService;
    private final GameBroadcaster broadcaster;

    public LobbyService(
            DeckRepository deckRepository,
            CardRepository cardRepository,
            ImageStorageService imageStorageService,
            GameBroadcaster broadcaster
    ) {
        this.deckRepository = deckRepository;
        this.cardRepository = cardRepository;
        this.imageStorageService = imageStorageService;
        this.broadcaster = broadcaster;
    }

    private static String leaveKey(String gameId, String clientId) {
        return gameId + "|" + clientId;
    }

    public List<LobbySummary> listGames() {
        List<LobbySummary> list = games.values().stream()
                .map(this::toSummary)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        log.info("listGames: {} room(s) {}", list.size(), list.stream().map(LobbySummary::id).toList());
        return list;
    }

    public LobbySummary createGame(String name, ClientIdentity identity) {
        Game game = new Game(name.trim(), identity.clientId());
        Player host = new Player(identity.clientId(), identity.userId(), identity.displayName(), identity.avatarUrl());
        game.getPlayers().put(host.getClientId(), host);
        games.put(game.getId(), game);
        log.info("createGame id={} name='{}' host={} totalRooms={}",
                game.getId(), game.getName(), identity.clientId(), games.size());
        return toSummary(game);
    }

    public GameStateView join(String gameId, ClientIdentity identity) {
        cancelPendingRemoval(gameId, identity.clientId());
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            joinLocked(game, identity.clientId(), identity.userId(), identity.displayName(), identity.avatarUrl());
            GameStateView state = toState(game, identity.clientId());
            broadcastLocked(game);
            return state;
        } finally {
            game.lock().unlock();
        }
    }

    /**
     * Bind a WS session to an existing seat when possible.
     * Prefer cookie/session identity; if that seat is missing but the browser's
     * query clientId is already in the room (guest→login mismatch), keep that seat.
     *
     * @return clientId to use for this WebSocket session
     */
    public String ensureJoinedForWs(
            String gameId,
            String identityClientId,
            String queryClientId,
            Long userId,
            String displayName,
            String avatarUrl
    ) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            String seatId = pickSeatClientId(game, identityClientId, queryClientId);
            cancelPendingRemoval(gameId, seatId);
            joinLocked(game, seatId, userId, displayName, avatarUrl);
            return seatId;
        } finally {
            game.lock().unlock();
        }
    }

    private static String pickSeatClientId(Game game, String identityClientId, String queryClientId) {
        if (identityClientId != null && game.getPlayer(identityClientId) != null) {
            return identityClientId;
        }
        if (queryClientId != null && !queryClientId.isBlank() && game.getPlayer(queryClientId) != null) {
            return queryClientId;
        }
        if (identityClientId != null && !identityClientId.isBlank()) {
            return identityClientId;
        }
        return queryClientId;
    }

    private void joinLocked(
            Game game,
            String clientId,
            Long userId,
            String displayName,
            String avatarUrl
    ) {
        Player existing = game.getPlayer(clientId);
        if (existing != null) {
            existing.setConnected(true);
            if (displayName != null && !displayName.isBlank()) {
                existing.setDisplayName(displayName);
            }
            existing.setAvatarUrl(avatarUrl);
            if (userId != null) {
                existing.setUserId(userId);
            }
            log.info("join rejoin game={} client={} players={}", game.getId(), clientId, game.getPlayers().size());
            return;
        }
        if (game.getStatus() != GameStatus.WAITING) {
            throw new ApiException(HttpStatus.CONFLICT, "game_started", "Game already started");
        }
        if (game.humanPlayers().size() >= Game.MAX_PLAYERS) {
            throw new ApiException(HttpStatus.CONFLICT, "game_full", "Lobby is full");
        }
        String name = displayName == null || displayName.isBlank() ? "Игрок" : displayName;
        Player player = new Player(clientId, userId, name, avatarUrl);
        game.getPlayers().put(player.getClientId(), player);
        log.info("join new game={} client={} players={}", game.getId(), clientId, game.getPlayers().size());
    }

    /**
     * Explicit leave. In WAITING lobby the player is removed; after start they stay for rejoin.
     */
    public void leave(String gameId, String clientId) {
        cancelPendingRemoval(gameId, clientId);
        Game game = games.get(gameId);
        if (game == null) {
            return;
        }
        game.lock().lock();
        try {
            if (game.getStatus() == GameStatus.WAITING) {
                removeFromLobbyLocked(game, clientId, "leave");
            } else {
                Player player = game.getPlayer(clientId);
                if (player != null) {
                    player.setConnected(false);
                    broadcastLocked(game);
                    log.info("leave (playing, keep seat) game={} client={}", gameId, clientId);
                }
            }
        } finally {
            game.lock().unlock();
        }
    }

    public GameStateView getState(String gameId, ClientIdentity identity) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            ensureMember(game, identity.clientId());
            return toState(game, identity.clientId());
        } finally {
            game.lock().unlock();
        }
    }

    public GameStateView selectDecks(String gameId, ClientIdentity identity, Set<Long> deckIds) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            Player player = ensureHumanMember(game, identity.clientId());
            if (game.getStage() != Stage.LOBBY) {
                throw new ApiException(HttpStatus.CONFLICT, "bad_stage", "Deck voting is only in lobby");
            }
            Set<Long> requested = deckIds == null ? Set.of() : deckIds;
            validateDeckSelection(requested, identity);
            player.getSelectedDeckIds().clear();
            player.getSelectedDeckIds().addAll(requested);
            player.setReady(false);
            GameStateView state = toState(game, identity.clientId());
            broadcastLocked(game);
            return state;
        } finally {
            game.lock().unlock();
        }
    }

    /** Host-only: add or remove the bot player in lobby. */
    public GameStateView setBot(String gameId, ClientIdentity identity, boolean enabled) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            ensureHumanMember(game, identity.clientId());
            if (!identity.clientId().equals(game.getHostClientId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "not_host", "Only the room host can toggle the bot");
            }
            if (game.getStage() != Stage.LOBBY || game.getStatus() != GameStatus.WAITING) {
                throw new ApiException(HttpStatus.CONFLICT, "bad_stage", "Bot only in lobby");
            }
            if (enabled) {
                addBotLocked(game);
            } else {
                removeBotLocked(game);
            }
            game.getBotAddVotes().clear();
            GameStateView state = toState(game, identity.clientId());
            broadcastLocked(game);
            return state;
        } finally {
            game.lock().unlock();
        }
    }

    /** @deprecated kept for old clients; host-only add. */
    public GameStateView voteAddBot(String gameId, ClientIdentity identity, boolean yes) {
        return setBot(gameId, identity, yes);
    }

    private void addBotLocked(Game game) {
        if (game.hasBot()) {
            return;
        }
        if (game.getPlayers().size() >= Game.MAX_PLAYERS + 1) {
            throw new ApiException(HttpStatus.CONFLICT, "game_full", "Lobby is full");
        }
        Player bot = new Player(Game.BOT_CLIENT_ID, null, Game.BOT_NAME, null, true);
        game.getPlayers().put(bot.getClientId(), bot);
        log.info("bot added to game={}", game.getId());
    }

    private void removeBotLocked(Game game) {
        Player removed = game.getPlayers().remove(Game.BOT_CLIENT_ID);
        if (removed != null) {
            log.info("bot removed from game={}", game.getId());
        }
    }

    public GameStateView setReady(String gameId, ClientIdentity identity, boolean ready) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            Player player = ensureHumanMember(game, identity.clientId());
            if (game.getStage() != Stage.LOBBY) {
                throw new ApiException(HttpStatus.CONFLICT, "bad_stage", "Ready flag only in lobby");
            }
            if (ready && player.getSelectedDeckIds().isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "decks_required", "Select at least one deck");
            }
            player.setReady(ready);
            if (ready) {
                try {
                    maybeStart(game);
                } catch (ApiException ex) {
                    player.setReady(false);
                    throw ex;
                }
            }
            GameStateView state = toState(game, identity.clientId());
            broadcastLocked(game);
            return state;
        } finally {
            game.lock().unlock();
        }
    }

    public void onWsConnected(String gameId, String clientId) {
        cancelPendingRemoval(gameId, clientId);
        Game game = games.get(gameId);
        if (game == null) {
            return;
        }
        game.lock().lock();
        try {
            Player player = game.getPlayer(clientId);
            if (player != null) {
                player.setConnected(true);
                broadcastLocked(game);
            }
        } finally {
            game.lock().unlock();
        }
    }

    public void onWsDisconnected(String gameId, String clientId) {
        Game game = games.get(gameId);
        if (game == null) {
            return;
        }
        game.lock().lock();
        try {
            Player player = game.getPlayer(clientId);
            if (player == null) {
                return;
            }
            player.setConnected(false);
            if (game.getStatus() == GameStatus.WAITING) {
                scheduleLobbyRemoval(gameId, clientId);
                broadcastLocked(game);
            } else {
                broadcastLocked(game);
                log.info("ws disconnect (playing, keep seat) game={} client={}", gameId, clientId);
            }
        } finally {
            game.lock().unlock();
        }
    }

    private void scheduleLobbyRemoval(String gameId, String clientId) {
        String key = leaveKey(gameId, clientId);
        ScheduledFuture<?> previous = pendingLobbyRemovals.remove(key);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> future = leaveScheduler.schedule(() -> {
            pendingLobbyRemovals.remove(key);
            Game game = games.get(gameId);
            if (game == null) {
                return;
            }
            game.lock().lock();
            try {
                if (game.getStatus() != GameStatus.WAITING) {
                    return;
                }
                Player player = game.getPlayer(clientId);
                if (player == null || player.isConnected()) {
                    return;
                }
                removeFromLobbyLocked(game, clientId, "ws-timeout");
            } finally {
                game.lock().unlock();
            }
        }, LOBBY_LEAVE_GRACE_MS, TimeUnit.MILLISECONDS);
        pendingLobbyRemovals.put(key, future);
        log.info("scheduled lobby removal game={} client={} in {}ms", gameId, clientId, LOBBY_LEAVE_GRACE_MS);
    }

    private void cancelPendingRemoval(String gameId, String clientId) {
        ScheduledFuture<?> future = pendingLobbyRemovals.remove(leaveKey(gameId, clientId));
        if (future != null) {
            future.cancel(false);
            log.info("cancelled lobby removal game={} client={}", gameId, clientId);
        }
    }

    private void removeFromLobbyLocked(Game game, String clientId, String reason) {
        if (Game.BOT_CLIENT_ID.equals(clientId)) {
            return;
        }
        Player removed = game.getPlayers().remove(clientId);
        if (removed == null) {
            return;
        }
        game.getBotAddVotes().remove(clientId);
        log.info("remove from lobby game={} client={} reason={} left={}",
                game.getId(), clientId, reason, game.getPlayers().size());
        if (game.humanPlayers().isEmpty()) {
            games.remove(game.getId(), game);
            log.info("deleted empty lobby {}", game.getId());
            return;
        }
        broadcastLocked(game);
    }

    public void submitAssociation(String gameId, String clientId, String association, String cardId) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            Player player = ensureHumanMember(game, clientId);
            if (game.getStage() != Stage.THEME_SELECTION) {
                throw new ApiException(HttpStatus.CONFLICT, "bad_stage", "Not association stage");
            }
            if (!player.getClientId().equals(game.master().getClientId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "not_master", "Only storyteller can set association");
            }
            if (association == null || association.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "association_required", "Association required");
            }
            if (!player.getHand().contains(cardId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "card_not_in_hand", "Card not in hand");
            }
            player.getHand().remove(cardId);
            player.setPlayedCardId(cardId);
            game.setAssociation(association.trim());
            game.setMasterCardId(cardId);
            game.getTableCards().clear();
            game.getTableCards().add(new TableCard(cardId, clientId));
            game.setStage(Stage.CARD_COLLECTION);
            runBotsLocked(game);
            maybeAdvanceFromCollection(game);
            broadcastLocked(game);
        } finally {
            game.lock().unlock();
        }
    }

    public void playCard(String gameId, String clientId, String cardId) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            Player player = ensureHumanMember(game, clientId);
            playCardLocked(game, player, cardId);
            broadcastLocked(game);
        } finally {
            game.lock().unlock();
        }
    }

    private void playCardLocked(Game game, Player player, String cardId) {
        if (game.getStage() != Stage.CARD_COLLECTION) {
            throw new ApiException(HttpStatus.CONFLICT, "bad_stage", "Not card collection stage");
        }
        if (player.getClientId().equals(game.master().getClientId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "master_cannot_play", "Storyteller already played");
        }
        if (player.getPlayedCardId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "already_played", "Already played a card");
        }
        if (!player.getHand().contains(cardId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "card_not_in_hand", "Card not in hand");
        }
        player.getHand().remove(cardId);
        player.setPlayedCardId(cardId);
        game.getTableCards().add(new TableCard(cardId, clientIdOf(player)));
        maybeAdvanceFromCollection(game);
    }

    private static String clientIdOf(Player player) {
        return player.getClientId();
    }

    private void maybeAdvanceFromCollection(Game game) {
        boolean allPlayed = game.playerList().stream().allMatch(p ->
                p.getPlayedCardId() != null || (p.isBot() && p.getHand().isEmpty()));
        if (allPlayed) {
            Collections.shuffle(game.getTableCards());
            game.setStage(Stage.VOTING);
        }
    }

    private void runBotsLocked(Game game) {
        if (game.getStage() != Stage.CARD_COLLECTION) {
            return;
        }
        Player bot = game.getPlayer(Game.BOT_CLIENT_ID);
        if (bot == null || bot.getPlayedCardId() != null) {
            return;
        }
        if (bot.getHand().isEmpty()) {
            return;
        }
        int idx = ThreadLocalRandom.current().nextInt(bot.getHand().size());
        String cardId = bot.getHand().get(idx);
        playCardLocked(game, bot, cardId);
    }

    public void vote(String gameId, String clientId, String voteCardId) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            Player player = ensureHumanMember(game, clientId);
            if (game.getStage() != Stage.VOTING) {
                throw new ApiException(HttpStatus.CONFLICT, "bad_stage", "Not voting stage");
            }
            if (player.getClientId().equals(game.master().getClientId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "master_cannot_vote", "Storyteller does not vote");
            }
            if (player.getVotedCardId() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "already_voted", "Already voted");
            }
            boolean onTable = game.getTableCards().stream().anyMatch(c -> c.cardId().equals(voteCardId));
            if (!onTable) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "card_not_on_table", "Card not on table");
            }
            if (voteCardId.equals(player.getPlayedCardId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_vote_own", "Cannot vote for your own card");
            }
            player.setVotedCardId(voteCardId);

            Player master = game.master();
            boolean allVoted = game.playerList().stream()
                    .filter(p -> !p.isBot())
                    .filter(p -> master == null || !p.getClientId().equals(master.getClientId()))
                    .allMatch(p -> p.getVotedCardId() != null);
            if (allVoted) {
                scoreRound(game);
                game.setStage(Stage.RESULTS);
            }
            broadcastLocked(game);
        } finally {
            game.lock().unlock();
        }
    }

    public void nextRound(String gameId, String clientId) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            ensureMember(game, clientId);
            if (game.getStage() != Stage.RESULTS) {
                throw new ApiException(HttpStatus.CONFLICT, "bad_stage", "Not results stage");
            }
            // Any connected player can advance; typically after everyone saw results.
            startNextRound(game);
            broadcastLocked(game);
        } finally {
            game.lock().unlock();
        }
    }

    public GameStateView stateFor(String gameId, String clientId) {
        Game game = requireGame(gameId);
        game.lock().lock();
        try {
            ensureMember(game, clientId);
            return toState(game, clientId);
        } finally {
            game.lock().unlock();
        }
    }

    private void maybeStart(Game game) {
        List<Player> humans = game.humanPlayers();
        if (humans.size() < Game.MIN_PLAYERS) {
            return;
        }
        if (!humans.stream().allMatch(Player::isReady)) {
            return;
        }
        // Each deck id once, even if several players picked the same deck.
        Set<Long> deckIds = new LinkedHashSet<>();
        for (Player player : humans) {
            deckIds.addAll(player.getSelectedDeckIds());
        }
        if (deckIds.isEmpty()) {
            return;
        }
        List<Card> cards = cardRepository.findByDeckIdInOrderByIdAsc(deckIds);
        List<Player> allPlayers = game.playerList();
        int needed = allPlayers.size() * Game.HAND_SIZE;
        // Deduplicate card ids across decks.
        LinkedHashSet<Long> uniqueCardIds = new LinkedHashSet<>();
        Map<Long, Card> byId = new LinkedHashMap<>();
        for (Card card : cards) {
            if (uniqueCardIds.add(card.getId())) {
                byId.put(card.getId(), card);
            }
        }
        if (uniqueCardIds.size() < needed) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "not_enough_cards",
                    "Need at least " + needed + " unique cards in selected decks"
            );
        }

        game.getDrawPile().clear();
        game.getDiscardPile().clear();
        game.getCardImageUrls().clear();
        for (Long cardPk : uniqueCardIds) {
            Card card = byId.get(cardPk);
            String id = String.valueOf(card.getId());
            game.getDrawPile().add(id);
            game.getCardImageUrls().put(id, imageStorageService.presignedUrl(card.getImageKey()));
        }
        game.shuffleDrawPile();

        for (Player player : allPlayers) {
            player.getHand().clear();
            player.setScore(0);
            player.resetRound();
            for (int i = 0; i < Game.HAND_SIZE; i++) {
                String cardId = game.drawCard();
                if (cardId != null) {
                    player.getHand().add(cardId);
                }
            }
        }

        game.setMasterIndex(0);
        game.setRoundNumber(1);
        game.setAssociation(null);
        game.setMasterCardId(null);
        game.getTableCards().clear();
        game.getLastRoundScores().clear();
        game.setStatus(GameStatus.PLAYING);
        game.setStage(Stage.THEME_SELECTION);
    }

    private void scoreRound(Game game) {
        Player master = game.master();
        String masterCard = game.getMasterCardId();
        List<Player> voters = game.playerList().stream()
                .filter(p -> !p.isBot())
                .filter(p -> master == null || !p.getClientId().equals(master.getClientId()))
                .toList();

        long correctVotes = voters.stream()
                .filter(p -> masterCard.equals(p.getVotedCardId()))
                .count();

        Map<String, Integer> deltas = new LinkedHashMap<>();
        for (Player p : game.playerList()) {
            deltas.put(p.getClientId(), 0);
        }

        Map<String, Long> votesPerCard = voters.stream()
                .filter(p -> p.getVotedCardId() != null)
                .collect(Collectors.groupingBy(Player::getVotedCardId, Collectors.counting()));

        if (!voters.isEmpty() && correctVotes == voters.size()) {
            // Everyone found the storyteller: storyteller 0, others +2.
            for (Player voter : voters) {
                voter.addScore(2);
                deltas.merge(voter.getClientId(), 2, Integer::sum);
            }
        } else if (correctVotes == 0) {
            // Nobody found the storyteller: +1 per chip on your card (bot ×3).
            awardChipPoints(game, votesPerCard, deltas, true);
        } else {
            // Mixed: storyteller +3, correct guessers +3, chips except on storyteller's card (bot ×3).
            if (master != null) {
                master.addScore(3);
                deltas.merge(master.getClientId(), 3, Integer::sum);
            }
            for (Player voter : voters) {
                if (masterCard.equals(voter.getVotedCardId())) {
                    voter.addScore(3);
                    deltas.merge(voter.getClientId(), 3, Integer::sum);
                }
            }
            awardChipPoints(game, votesPerCard, deltas, false);
        }

        game.getLastRoundScores().clear();
        game.getLastRoundScores().putAll(deltas);
    }

    /**
     * @param includeMaster when true (nobody guessed), chips on the storyteller card also score;
     *                      when false (partial), storyteller does not get chip points.
     */
    private void awardChipPoints(
            Game game,
            Map<String, Long> votesPerCard,
            Map<String, Integer> deltas,
            boolean includeMaster
    ) {
        Player master = game.master();
        String masterId = master == null ? null : master.getClientId();
        for (TableCard tableCard : game.getTableCards()) {
            Player owner = game.getPlayer(tableCard.ownerClientId());
            if (owner == null) {
                continue;
            }
            if (!includeMaster && masterId != null && masterId.equals(owner.getClientId())) {
                continue;
            }
            long votes = votesPerCard.getOrDefault(tableCard.cardId(), 0L);
            if (votes <= 0) {
                continue;
            }
            int multiplier = owner.isBot() ? 3 : 1;
            int gained = (int) votes * multiplier;
            owner.addScore(gained);
            deltas.merge(owner.getClientId(), gained, Integer::sum);
        }
    }

    private void startNextRound(Game game) {
        for (TableCard tableCard : game.getTableCards()) {
            game.getDiscardPile().add(tableCard.cardId());
        }
        game.getTableCards().clear();
        game.setAssociation(null);
        game.setMasterCardId(null);
        game.getLastRoundScores().clear();

        // One refill card per player if the draw pile still has cards; discard never returns.
        for (Player player : game.playerList()) {
            player.resetRound();
            String cardId = game.drawCard();
            if (cardId != null) {
                player.getHand().add(cardId);
            }
        }

        if (!anyHumanHasCards(game)) {
            finishGame(game);
            return;
        }

        game.setMasterIndex(game.getMasterIndex() + 1);
        int humans = Math.max(1, game.humanPlayers().size());
        boolean found = false;
        for (int i = 0; i < humans; i++) {
            Player nextMaster = game.master();
            if (nextMaster != null && !nextMaster.getHand().isEmpty()) {
                found = true;
                break;
            }
            game.setMasterIndex(game.getMasterIndex() + 1);
        }
        if (!found) {
            finishGame(game);
            return;
        }

        game.setRoundNumber(game.getRoundNumber() + 1);
        game.setStage(Stage.THEME_SELECTION);
    }

    private static boolean anyHumanHasCards(Game game) {
        return game.humanPlayers().stream().anyMatch(p -> !p.getHand().isEmpty());
    }

    private void finishGame(Game game) {
        game.setStatus(GameStatus.FINISHED);
        game.setStage(Stage.GAME_OVER);
        game.setAssociation(null);
        game.setMasterCardId(null);
        log.info("game finished id={}", game.getId());
    }

    private void validateDeckSelection(Set<Long> deckIds, ClientIdentity identity) {
        for (Long deckId : deckIds) {
            Deck deck = deckRepository.findById(deckId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "Deck not found"));
            if (deck.isPublic()) {
                continue;
            }
            if (identity.userId() == null || !identity.userId().equals(deck.getOwnerId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "private_deck", "Private deck belongs to another user");
            }
        }
    }

    private void broadcastLocked(Game game) {
        broadcaster.broadcast(game.getId(), clientId -> toState(game, clientId));
    }

    private Player ensureMember(Game game, String clientId) {
        Player player = game.getPlayer(clientId);
        if (player == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_member", "Join the lobby first");
        }
        return player;
    }

    private Player ensureHumanMember(Game game, String clientId) {
        Player player = ensureMember(game, clientId);
        if (player.isBot()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "bot_action", "Bot cannot perform this action");
        }
        return player;
    }

    private Game requireGame(String gameId) {
        Game game = games.get(gameId);
        if (game == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Game not found");
        }
        return game;
    }

    private LobbySummary toSummary(Game game) {
        return new LobbySummary(
                game.getId(),
                game.getName(),
                game.getPlayers().size(),
                Game.MAX_PLAYERS + (game.hasBot() ? 1 : 0),
                game.getStatus(),
                game.getStage()
        );
    }

    private GameStateView toState(Game game, String viewerClientId) {
        Player viewer = game.getPlayer(viewerClientId);
        Player master = game.master();
        boolean youMaster = master != null && master.getClientId().equals(viewerClientId);
        Stage stage = game.getStage();

        List<PlayerView> players = new ArrayList<>();
        for (Player p : game.playerList()) {
            players.add(new PlayerView(
                    p.getClientId(),
                    p.getDisplayName(),
                    p.getAvatarUrl(),
                    p.isReady(),
                    p.isConnected(),
                    p.getUserId() != null,
                    p.getScore(),
                    Set.copyOf(p.getSelectedDeckIds()),
                    p.getPlayedCardId() != null,
                    p.getVotedCardId() != null,
                    master != null && master.getClientId().equals(p.getClientId()),
                    p.isBot()
            ));
        }

        List<HandCard> hand = List.of();
        if (viewer != null && !viewer.isBot()) {
            hand = viewer.getHand().stream()
                    .map(id -> new HandCard(id, game.getCardImageUrls().get(id)))
                    .toList();
        }

        List<TableCardView> tableViews = new ArrayList<>();
        if (stage == Stage.VOTING || stage == Stage.RESULTS || stage == Stage.GAME_OVER) {
            for (TableCard card : game.getTableCards()) {
                Player owner = game.getPlayer(card.ownerClientId());
                boolean revealOwner = stage == Stage.RESULTS || stage == Stage.GAME_OVER;
                tableViews.add(new TableCardView(
                        card.cardId(),
                        game.getCardImageUrls().get(card.cardId()),
                        revealOwner ? card.ownerClientId() : null,
                        revealOwner && owner != null ? owner.getDisplayName() : null
                ));
            }
        }

        Set<Long> yourDecks = viewer == null ? Set.of() : Set.copyOf(viewer.getSelectedDeckIds());
        List<Long> available = availableDeckIdsFor(viewer);

        String masterCardId = (stage == Stage.RESULTS || stage == Stage.GAME_OVER)
                ? game.getMasterCardId() : null;

        boolean youAreHost = viewerClientId != null && viewerClientId.equals(game.getHostClientId());

        return new GameStateView(
                game.getId(),
                game.getName(),
                game.getStatus(),
                stage,
                game.getRoundNumber(),
                stage == Stage.LOBBY ? null : game.getAssociation(),
                game.getTableCards().size(),
                game.getPlayers().size(),
                youMaster,
                viewer != null && viewer.isReady(),
                players,
                hand,
                tableViews,
                available,
                yourDecks,
                Map.copyOf(game.getLastRoundScores()),
                masterCardId,
                SCORING_HINT,
                game.hasBot(),
                false,
                0,
                0,
                game.getDrawPile().size(),
                viewerClientId,
                youAreHost,
                viewer != null ? viewer.getPlayedCardId() : null
        );
    }

    private List<Long> availableDeckIdsFor(Player viewer) {
        if (viewer != null && viewer.getUserId() != null) {
            return deckRepository.findVisibleToUser(viewer.getUserId()).stream()
                    .map(Deck::getId)
                    .toList();
        }
        return deckRepository.findByIsPublicTrueOrderByIdAsc().stream()
                .map(Deck::getId)
                .toList();
    }
}
