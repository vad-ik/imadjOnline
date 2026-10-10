package com.github.vad_ik.imadjOnline.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Player {
    private final String clientId;
    private Long userId;
    private String displayName;
    private String avatarUrl;
    private boolean ready;
    private final Set<Long> selectedDeckIds = new HashSet<>();
    private final List<String> hand = new ArrayList<>();
    private int score;
    private String playedCardId;
    private String votedCardId;
    private boolean connected;
    private boolean bot;

    public Player(String clientId, Long userId, String displayName, String avatarUrl) {
        this(clientId, userId, displayName, avatarUrl, false);
    }

    public Player(String clientId, Long userId, String displayName, String avatarUrl, boolean bot) {
        this.clientId = clientId;
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.connected = true;
        this.bot = bot;
        if (bot) {
            this.ready = true;
        }
    }

    public String getClientId() {
        return clientId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public Set<Long> getSelectedDeckIds() {
        return selectedDeckIds;
    }

    public List<String> getHand() {
        return hand;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public void addScore(int delta) {
        this.score += delta;
    }

    public String getPlayedCardId() {
        return playedCardId;
    }

    public void setPlayedCardId(String playedCardId) {
        this.playedCardId = playedCardId;
    }

    public String getVotedCardId() {
        return votedCardId;
    }

    public void setVotedCardId(String votedCardId) {
        this.votedCardId = votedCardId;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isBot() {
        return bot;
    }

    public void resetRound() {
        playedCardId = null;
        votedCardId = null;
    }
}
