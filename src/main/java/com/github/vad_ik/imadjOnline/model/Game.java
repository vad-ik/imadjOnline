package com.github.vad_ik.imadjOnline.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public class Game {
    public static final int MIN_PLAYERS = 3;
    public static final int MAX_PLAYERS = 8;
    public static final int HAND_SIZE = 6;
    public static final String BOT_CLIENT_ID = "bot:kisunya";
    public static final String BOT_NAME = "Кисонька Сосисонька";

    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String name;
    private final String hostClientId;
    private final ReentrantLock lock = new ReentrantLock();

    private GameStatus status = GameStatus.WAITING;
    private Stage stage = Stage.LOBBY;
    private final Map<String, Player> players = new LinkedHashMap<>();

    private final List<String> drawPile = new ArrayList<>();
    private final List<String> discardPile = new ArrayList<>();
    private final Map<String, String> cardImageUrls = new LinkedHashMap<>();

    private int masterIndex;
    private String association;
    private String masterCardId;
    private final List<TableCard> tableCards = new ArrayList<>();
    private final Map<String, Integer> lastRoundScores = new LinkedHashMap<>();
    private int roundNumber;
    /** Human players who voted to add the bot. */
    private final Set<String> botAddVotes = new HashSet<>();

    public Game(String name, String hostClientId) {
        this.name = name;
        this.hostClientId = hostClientId;
    }

    public ReentrantLock lock() {
        return lock;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getHostClientId() {
        return hostClientId;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public Map<String, Player> getPlayers() {
        return players;
    }

    public List<Player> playerList() {
        return new ArrayList<>(players.values());
    }

    public Player getPlayer(String clientId) {
        return players.get(clientId);
    }

    public List<String> getDrawPile() {
        return drawPile;
    }

    public List<String> getDiscardPile() {
        return discardPile;
    }

    public Map<String, String> getCardImageUrls() {
        return cardImageUrls;
    }

    public int getMasterIndex() {
        return masterIndex;
    }

    public void setMasterIndex(int masterIndex) {
        this.masterIndex = masterIndex;
    }

    public List<Player> humanPlayers() {
        return playerList().stream().filter(p -> !p.isBot()).toList();
    }

    public Player master() {
        List<Player> humans = humanPlayers();
        if (humans.isEmpty()) {
            return null;
        }
        return humans.get(Math.floorMod(masterIndex, humans.size()));
    }

    public Set<String> getBotAddVotes() {
        return botAddVotes;
    }

    public boolean hasBot() {
        return players.containsKey(BOT_CLIENT_ID);
    }

    public String getAssociation() {
        return association;
    }

    public void setAssociation(String association) {
        this.association = association;
    }

    public String getMasterCardId() {
        return masterCardId;
    }

    public void setMasterCardId(String masterCardId) {
        this.masterCardId = masterCardId;
    }

    public List<TableCard> getTableCards() {
        return tableCards;
    }

    public Map<String, Integer> getLastRoundScores() {
        return lastRoundScores;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public void setRoundNumber(int roundNumber) {
        this.roundNumber = roundNumber;
    }

    public void shuffleDrawPile() {
        Collections.shuffle(drawPile);
    }

    /** Draw from the remaining deck only — discarded cards never return. */
    public String drawCard() {
        if (drawPile.isEmpty()) {
            return null;
        }
        return drawPile.removeLast();
    }

    public record TableCard(String cardId, String ownerClientId) {
    }
}
