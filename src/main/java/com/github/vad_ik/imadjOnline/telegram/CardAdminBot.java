package com.github.vad_ik.imadjOnline.telegram;

import com.github.vad_ik.imadjOnline.db.entity.Deck;
import com.github.vad_ik.imadjOnline.service.DeckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class CardAdminBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(CardAdminBot.class);

    private enum Step {
        IDLE,
        NEW_DECK_NAME,
        ADD_CARD_PHOTO
    }

    private static final class Dialog {
        Step step = Step.IDLE;
        Long deckId;
    }

    private final String token;
    private final TelegramClient telegramClient;
    private final DeckService deckService;
    private final Map<Long, Dialog> dialogs = new ConcurrentHashMap<>();

    public CardAdminBot(
            @Value("${telegram.bot.token}") String token,
            DeckService deckService
    ) {
        this.token = token;
        this.deckService = deckService;
        this.telegramClient = new OkHttpTelegramClient(token);
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                onCallback(update.getCallbackQuery());
                return;
            }
            if (!update.hasMessage()) {
                return;
            }
            onMessage(update.getMessage());
        } catch (Exception e) {
            log.error("Bot error", e);
            Long chatId = resolveChatId(update);
            if (chatId != null) {
                clear(chatId);
                reply(chatId, "Ошибка: " + e.getMessage());
            }
        }
    }

    private void onMessage(Message message) throws Exception {
        long chatId = message.getChatId();

        if (message.hasText()) {
            String text = message.getText().trim();
            if (text.startsWith("/")) {
                startCommand(chatId, text);
                return;
            }
        }

        Dialog dialog = dialogs.get(chatId);
        if (dialog == null || dialog.step == Step.IDLE) {
            if (message.hasText()) {
                reply(chatId, "Не понял. /help");
            }
            return;
        }

        switch (dialog.step) {
            case NEW_DECK_NAME -> onNewDeckName(chatId, message);
            case ADD_CARD_PHOTO -> onAddCardPhoto(chatId, message);
            default -> reply(chatId, "Не понял. /help");
        }
    }

    private void onCallback(CallbackQuery callback) throws Exception {
        String data = callback.getData();
        long chatId = callback.getMessage().getChatId();

        telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callback.getId())
                .build());

        if (data == null || !data.startsWith("addcard:")) {
            return;
        }

        long deckId = Long.parseLong(data.substring("addcard:".length()));
        deckService.requireDeck(deckId);
        Dialog dialog = dialog(chatId);
        dialog.deckId = deckId;
        dialog.step = Step.ADD_CARD_PHOTO;
        reply(chatId, "Пришлите фото карты:");
    }

    private void startCommand(long chatId, String text) {
        switch (text) {
            case "/start", "/help" -> {
                clear(chatId);
                reply(chatId, """
                        Команды:
                        /newdeck — создать колоду
                        /addcard — добавить карту
                        """);
            }
            case "/newdeck" -> {
                dialog(chatId).step = Step.NEW_DECK_NAME;
                reply(chatId, "Введите название новой колоды:");
            }
            case "/addcard" -> {
                clear(chatId);
                askDeck(chatId);
            }
            default -> reply(chatId, "Неизвестная команда. /help");
        }
    }

    private void askDeck(long chatId) {
        List<Deck> decks = deckService.listDecks();
        if (decks.isEmpty()) {
            reply(chatId, "Колод пока нет. Создай через /newdeck");
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Deck deck : decks) {
            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(deck.getName())
                    .callbackData("addcard:" + deck.getId())
                    .build();
            rows.add(new InlineKeyboardRow(button));
        }

        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Выберите колоду:")
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send deck keyboard to {}", chatId, e);
        }
    }

    private void onNewDeckName(long chatId, Message message) {
        if (!message.hasText() || message.getText().isBlank()) {
            reply(chatId, "Нужен текст. Введите название новой колоды:");
            return;
        }
        Deck deck = deckService.createDeck(message.getText().trim());
        clear(chatId);
        reply(chatId, "Колода «" + deck.getName() + "» создана.");
    }

    private void onAddCardPhoto(long chatId, Message message) throws Exception {
        if (!message.hasPhoto()) {
            reply(chatId, "Нужно фото. Пришлите фото карты:");
            return;
        }
        PhotoSize photo = message.getPhoto().getLast();
        File tgFile = telegramClient.execute(new GetFile(photo.getFileId()));
        String filename = tgFile.getFilePath() != null
                ? tgFile.getFilePath().substring(tgFile.getFilePath().lastIndexOf('/') + 1)
                : "card.jpg";

        byte[] bytes;
        try (InputStream in = telegramClient.downloadFileAsStream(tgFile)) {
            bytes = in.readAllBytes();
        }

        Dialog dialog = dialog(chatId);
        deckService.addCard(dialog.deckId, bytes, "image/jpeg", filename, null);
        clear(chatId);
        reply(chatId, "Карта добавлена.");
    }

    private Dialog dialog(long chatId) {
        return dialogs.computeIfAbsent(chatId, id -> new Dialog());
    }

    private void clear(long chatId) {
        dialogs.remove(chatId);
    }

    private void reply(long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send message to {}", chatId, e);
        }
    }

    private static Long resolveChatId(Update update) {
        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return null;
    }
}
