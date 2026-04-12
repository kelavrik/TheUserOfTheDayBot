package com.UserOfTheDayBot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
        try {
            Bot bot = new Bot(config);
            TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
            telegramBotsApi.registerBot(bot);
            bot.configureCommands();
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

}
