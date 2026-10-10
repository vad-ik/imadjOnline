package com.github.vad_ik.imadjOnline.controller;

import com.github.vad_ik.imadjOnline.auth.ClientIdentity;
import com.github.vad_ik.imadjOnline.auth.IdentitySupport;
import com.github.vad_ik.imadjOnline.dto.GameDtos.AssociationRequest;
import com.github.vad_ik.imadjOnline.dto.GameDtos.BotVoteRequest;
import com.github.vad_ik.imadjOnline.dto.GameDtos.CardActionRequest;
import com.github.vad_ik.imadjOnline.dto.GameDtos.CreateGameRequest;
import com.github.vad_ik.imadjOnline.dto.GameDtos.GameStateView;
import com.github.vad_ik.imadjOnline.dto.GameDtos.LobbySummary;
import com.github.vad_ik.imadjOnline.dto.GameDtos.SelectDecksRequest;
import com.github.vad_ik.imadjOnline.service.LobbyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@Tag(name = "Games / Lobby", description = """
        HTTP API лобби и ходов. Живые обновления — WebSocket `/ws/game`
        (спецификация AsyncAPI: `/docs/asyncapi.yaml`).
        """)
@RestController
@RequestMapping("/api/games")
public class LobbyController {

    private final LobbyService lobbyService;
    private final IdentitySupport identitySupport;

    public LobbyController(LobbyService lobbyService, IdentitySupport identitySupport) {
        this.lobbyService = lobbyService;
        this.identitySupport = identitySupport;
    }

    @Operation(summary = "Список комнат")
    @GetMapping
    public List<LobbySummary> list() {
        return lobbyService.listGames();
    }

    @Operation(summary = "Создать комнату", description = "Создатель становится хостом.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LobbySummary create(@Valid @RequestBody CreateGameRequest request, HttpServletRequest httpRequest) {
        return lobbyService.createGame(request.name(), identitySupport.current(httpRequest));
    }

    @Operation(summary = "Войти / переподключиться в комнату")
    @PostMapping("/{id}/join")
    public GameStateView join(@PathVariable String id, HttpServletRequest request) {
        return lobbyService.join(id, identitySupport.current(request));
    }

    @Operation(summary = "Состояние комнаты для текущего игрока")
    @GetMapping("/{id}")
    public GameStateView state(@PathVariable String id, HttpServletRequest request) {
        return lobbyService.getState(id, identitySupport.current(request));
    }

    @Operation(summary = "Выбрать колоды в лобби")
    @PostMapping("/{id}/decks")
    public GameStateView selectDecks(
            @PathVariable String id,
            @RequestBody SelectDecksRequest body,
            HttpServletRequest request
    ) {
        Set<Long> deckIds = body == null || body.deckIds() == null ? Set.of() : body.deckIds();
        return lobbyService.selectDecks(id, identitySupport.current(request), deckIds);
    }

    @Operation(summary = "Готов к старту")
    @PostMapping("/{id}/ready")
    public GameStateView ready(@PathVariable String id, HttpServletRequest request) {
        return lobbyService.setReady(id, identitySupport.current(request), true);
    }

    @Operation(summary = "Снять готовность")
    @PostMapping("/{id}/unready")
    public GameStateView unready(@PathVariable String id, HttpServletRequest request) {
        return lobbyService.setReady(id, identitySupport.current(request), false);
    }

    @Operation(summary = "Покинуть комнату")
    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String id, HttpServletRequest request) {
        ClientIdentity identity = identitySupport.current(request);
        lobbyService.leave(id, identity.clientId());
    }

    @Operation(summary = "Кисонька (устаревший путь)", description = "Синоним POST /bot, только для хоста.")
    @PostMapping("/{id}/bot-vote")
    public GameStateView botVote(
            @PathVariable String id,
            @RequestBody(required = false) BotVoteRequest body,
            HttpServletRequest request
    ) {
        boolean yes = body == null || body.yes();
        return lobbyService.setBot(id, identitySupport.current(request), yes);
    }

    @Operation(summary = "Добавить/убрать бота Кисоньку", description = "Только хост комнаты, только в лобби.")
    @PostMapping("/{id}/bot")
    public GameStateView setBot(
            @PathVariable String id,
            @RequestBody(required = false) BotVoteRequest body,
            HttpServletRequest request
    ) {
        boolean enabled = body == null || body.yes();
        return lobbyService.setBot(id, identitySupport.current(request), enabled);
    }

    @Operation(summary = "Ведущий загадывает тему и кладёт карту")
    @PostMapping("/{id}/association")
    public GameStateView association(
            @PathVariable String id,
            @Valid @RequestBody AssociationRequest body,
            HttpServletRequest request
    ) {
        ClientIdentity identity = identitySupport.current(request);
        lobbyService.submitAssociation(id, identity.clientId(), body.association(), body.cardId());
        return lobbyService.getState(id, identity);
    }

    @Operation(summary = "Положить карту на стол (не ведущий)")
    @PostMapping("/{id}/play")
    public GameStateView play(
            @PathVariable String id,
            @Valid @RequestBody CardActionRequest body,
            HttpServletRequest request
    ) {
        ClientIdentity identity = identitySupport.current(request);
        lobbyService.playCard(id, identity.clientId(), body.cardId());
        return lobbyService.getState(id, identity);
    }

    @Operation(summary = "Проголосовать за карту ведущего")
    @PostMapping("/{id}/vote")
    public GameStateView voteCard(
            @PathVariable String id,
            @Valid @RequestBody CardActionRequest body,
            HttpServletRequest request
    ) {
        ClientIdentity identity = identitySupport.current(request);
        lobbyService.vote(id, identity.clientId(), body.cardId());
        return lobbyService.getState(id, identity);
    }

    @Operation(summary = "Следующий раунд после итогов")
    @PostMapping("/{id}/next-round")
    public GameStateView nextRound(@PathVariable String id, HttpServletRequest request) {
        ClientIdentity identity = identitySupport.current(request);
        lobbyService.nextRound(id, identity.clientId());
        return lobbyService.getState(id, identity);
    }
}
