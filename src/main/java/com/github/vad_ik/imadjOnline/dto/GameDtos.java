package com.github.vad_ik.imadjOnline.dto;

import com.github.vad_ik.imadjOnline.model.GameStatus;
import com.github.vad_ik.imadjOnline.model.Stage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GameDtos {

    private GameDtos() {
    }

    public record CreateGameRequest(
            @NotBlank @Size(max = 80) String name
    ) {
    }

    public record SelectDecksRequest(Set<Long> deckIds) {
    }

    public record JoinResponse(String gameId, String clientId) {
    }

    public record LobbySummary(
            String id,
            String name,
            int playerCount,
            int maxPlayers,
            GameStatus status,
            Stage stage
    ) {
    }

    public record PlayerView(
            String clientId,
            String displayName,
            String avatarUrl,
            boolean ready,
            boolean connected,
            boolean registered,
            int score,
            Set<Long> selectedDeckIds,
            boolean hasPlayed,
            boolean hasVoted,
            boolean isMaster,
            boolean bot
    ) {
    }

    public record BotVoteRequest(boolean yes) {
    }

    public record RotateDeckRequest(Integer degrees) {
    }

    public record RotateDeckResponse(int rotated, int degrees) {
    }

    public record AssociationRequest(
            @NotBlank @Size(max = 120) String association,
            @NotBlank String cardId
    ) {
    }

    public record CardActionRequest(@NotBlank String cardId) {
    }

    public record HandCard(String id, String imageUrl) {
    }

    public record TableCardView(
            String id,
            String imageUrl,
            String ownerClientId,
            String ownerName
    ) {
    }

    public record GameStateView(
            String id,
            String name,
            GameStatus status,
            Stage stage,
            int roundNumber,
            String association,
            int tableCardCount,
            int playerCount,
            boolean youAreMaster,
            boolean youReady,
            List<PlayerView> players,
            List<HandCard> hand,
            List<TableCardView> tableCards,
            List<Long> availableDeckIds,
            Set<Long> yourSelectedDeckIds,
            Map<String, Integer> lastRoundScores,
            String masterCardId,
            String scoringHint,
            boolean hasBot,
            boolean youVotedAddBot,
            int botYesVotes,
            int botVotesNeeded,
            int drawPileSize,
            String yourClientId,
            boolean youAreHost,
            /** Card this viewer played this round (for graying own card while voting). */
            String yourPlayedCardId
    ) {
    }

    public record WsIncoming(
            String type,
            String association,
            String cardId,
            String voteCardId
    ) {
    }
}
