package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.Commands;
import com.UserOfTheDayBot.enums.DBColumns;
import com.UserOfTheDayBot.enums.Games;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import com.UserOfTheDayBot.exceptions.existedUserException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Bot extends TelegramLongPollingBot {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<String, Object> CHAT_LOCKS = new ConcurrentHashMap<String, Object>();
    private final AppConfig config;
    // Daemon scheduler — runs the year-end ceremony at midnight Jan 1 in BOT_TIMEZONE.
    // Daemon thread so it doesn't keep the JVM alive on its own.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "year-end-ceremony");
        t.setDaemon(true);
        return t;
    });

    //class for sending messages with delay
    class TimerSendingTask extends TimerTask {
        private String message;
        private String chatId;

        TimerSendingTask(String chatId, String message) {
            this.chatId = chatId;
            this.message = message;
        }

        @Override
        public void run() {
            sendMsg(chatId, message);
        }
    }

    private final String[] messagesForUserOfTheDay = {
            "\uD83C\uDF89 Сегодня красавчик дня - ",
            "ВНИМАНИЕ \uD83D\uDD25",
            "Ищем красавчика в этом чате",
            "Гадаем на бинарных опционах \uD83D\uDCCA",
            "Анализируем лунный гороскоп \uD83C\uDF16",
            "Лунная призма дай мне силу \uD83D\uDCAB",
            "СЕКТОР ПРИЗ НА БАРАБАНЕ \uD83C\uDFAF"
    };
    private final String[] messagesForLoserOfTheDay = {
            "\uD83C\uDF89 Сегодня пидор \uD83C\uDF08 дня - ",
            "ВНИМАНИЕ \uD83D\uDD25",
            "ФЕДЕРАЛЬНЫЙ \uD83D\uDD0D РОЗЫСК ПИДОРА \uD83D\uDEA8",
            "4 - спутник запущен \uD83D\uDE80",
            "3 - сводки Интерпола проверены \uD83D\uDE93",
            "2 - твои друзья опрошены \uD83D\uDE45",
            "1 - твой профиль в соцсетях проанализирован \uD83D\uDE40"

    };

    // Анимация для годовой церемонии. [0] — заголовок объявления (со %d для года),
    // [1..N-1] — suspense-сообщения, между ними MESSAGE_DELAY как у /run и /pidor.
    private final String[] yearHeroMessages = {
            "🏆 КРАСАВЧИК ГОДА %d — ",
            "ИТОГИ ПОДВЕДЕНЫ 🏁",
            "365 дней удачи позади 📅",
            "Складываем все победы за год 🧮",
            "Сверяем лунные циклы 🌙",
            "Корону уже несут 👑",
            "ГРАНД-ПРИ НА ГОДОВОМ БАРАБАНЕ 🎯"
    };

    private final String[] yearLoserMessages = {
            "🌈 ПИДОР ГОДА %d — ",
            "ИТОГОВАЯ СВОДКА ИНТЕРПОЛА 🚨",
            "365 дней наблюдения завершены 👁",
            "ФБР собрало все досье 🗂",
            "Глобальный розыск окончен 🚓",
            "Радужный флаг поднят 🏳",
            "АБСОЛЮТНЫЙ ЧЕМПИОН ГОДА 🏆🌈"
    };

    public Bot(AppConfig config) {
        this.config = config;
        scheduleNextYearEnd();
    }

    /** Schedule the New Year ceremony for 00:00:00 of next Jan 1 in BOT_TIMEZONE.
     *  After firing, the task re-schedules itself for the year after. */
    private void scheduleNextYearEnd() {
        ZoneId zone = ZoneId.of(config.getBotTimezone());
        ZonedDateTime now = ZonedDateTime.now(zone);
        // The year that will end at the next midnight Jan 1 we're scheduling for.
        final int finishedYear = now.getYear();
        ZonedDateTime target = LocalDate.of(finishedYear + 1, 1, 1).atStartOfDay(zone);
        long delayMs = ChronoUnit.MILLIS.between(now, target);
        if (delayMs <= 0) delayMs = 1000; // safety, should never happen
        scheduler.schedule(() -> {
            try {
                runYearEndCeremony(finishedYear);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                scheduleNextYearEnd();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        System.out.println("[year-end] ceremony scheduled for " + target + " (in " + (delayMs / 1000) + "s, finishedYear=" + finishedYear + ")");
    }

    /** Iterate every chat, send the New Year sheet, and reset that chat's stats. */
    private void runYearEndCeremony(int finishedYear) {
        System.out.println("[year-end] running ceremony for finishedYear=" + finishedYear);
        DBHandler dbHandler = new DBHandler(config);
        try {
            List<String> chatIds = dbHandler.getAllChatIds();
            for (String chatId : chatIds) {
                Object lock = CHAT_LOCKS.computeIfAbsent(chatId, k -> new Object());
                synchronized (lock) {
                    try {
                        announceYearEnd(chatId, finishedYear, dbHandler);
                        dbHandler.resetChatStats(chatId);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable t) {
                        // One bad chat shouldn't kill the ceremony for the rest.
                        t.printStackTrace();
                    }
                }
            }
        } finally {
            dbHandler.closeConnection();
        }
        System.out.println("[year-end] ceremony done for finishedYear=" + finishedYear);
    }

    /**
     * Годовая церемония для одного чата: поздравление → анимация «Красавчик
     * года» (один победитель — с максимальным user_day_counter) → анимация
     * «Пидор года» (один с максимальным loser_counter) → старт нового раунда.
     *
     * Анимации идут синхронно через Thread.sleep, лок чата держится до конца
     * церемонии — это гарантирует, что одновременный /run или /pidor не
     * прокинется между фазами и не использует частично сброшенное состояние.
     */
    private void announceYearEnd(String chatId, int finishedYear, DBHandler dbHandler) throws InterruptedException {
        List<UserForBD> users = dbHandler.getListOfPlayers(chatId);
        if (users.isEmpty()) return;

        UserForBD heroOfYear = null;
        UserForBD pidorOfYear = null;
        int heroCount = 0;
        int pidorCount = 0;
        for (UserForBD u : users) {
            if (u.getUserDayCounter() > heroCount) {
                heroCount = u.getUserDayCounter();
                heroOfYear = u;
            }
            if (u.getLoserDayCounter() > pidorCount) {
                pidorCount = u.getLoserDayCounter();
                pidorOfYear = u;
            }
        }
        // Если в этом году никто и /run, и /pidor ни разу не выиграл — молча
        // пропустить чат, не спамить пустой церемонией.
        if (heroOfYear == null && pidorOfYear == null) return;

        int messageDelayMs = 1500;

        // 1. Поздравление
        sendMsg(chatId,
                "🎄🎁🎉 С НОВЫМ ГОДОМ! 🎉🎁🎄\n" +
                "✨🌟💫🎆🎇💫🌟✨\n\n" +
                "Подводим итоги " + finishedYear + " года 🥁🥁🥁");
        Thread.sleep(2500);

        // 2. Анимация «Красавчик года»
        if (heroOfYear != null) {
            revealAnimated(chatId, yearHeroMessages, finishedYear, heroOfYear, heroCount, messageDelayMs);
            Thread.sleep(1500);
        }

        // 3. Анимация «Пидор года»
        if (pidorOfYear != null) {
            revealAnimated(chatId, yearLoserMessages, finishedYear, pidorOfYear, pidorCount, messageDelayMs);
            Thread.sleep(1500);
        }

        // 4. Старт нового раунда
        sendMsg(chatId,
                "🎊 Стартует розыгрыш " + (finishedYear + 1) + "! 🎊\n" +
                "Статистика обнулена, всем удачи в Новом Году! 🍾🥂🎈");
    }

    /**
     * Шлёт суспенс-сообщения [1..N-1] с задержкой messageDelayMs между ними,
     * затем финальный реверс: header (с подставленным годом) + имя победителя
     * и количество побед. Аналогично runGame, но синхронно — лок держится.
     */
    private void revealAnimated(String chatId, String[] messages, int finishedYear,
                                UserForBD winner, int count, int messageDelayMs) throws InterruptedException {
        for (int i = 1; i < messages.length; i++) {
            sendMsg(chatId, messages[i]);
            Thread.sleep(messageDelayMs);
        }
        String header = String.format(messages[0], finishedYear);
        sendMsg(chatId, header + winner.getNotificationName() + " — " + count + " раз(а)!");
    }

    public void configureCommands() throws TelegramApiException {
        List<BotCommand> commands = new ArrayList<BotCommand>();
        commands.add(new BotCommand("reg", "чтобы участвовать в розыгрыше"));
        commands.add(new BotCommand("delete", "чтобы сбежать с поля боя"));
        commands.add(new BotCommand("run", "запустить барабан усатого чтобы узнать красавчика дня"));
        commands.add(new BotCommand("pidor", "узнай кто пидор дня"));
        commands.add(new BotCommand("stats", "результаты игры Красавчик"));
        commands.add(new BotCommand("pidorstats", "результаты игры Пидор дня"));
        execute(new SetMyCommands(commands, null, null));
    }

    /*method that gets a message
    * then handles it and does action according to command
     */
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String message = update.getMessage().getText();
        if(!message.startsWith("/")){
            return;
        }
        int commandEnd = message.lastIndexOf("@" + getBotUsername());
        if(commandEnd == -1){
            commandEnd = message.length();
        }

        Commands command;
        try {
            command = Commands.valueOf(message.substring(1, commandEnd));
        } catch (IllegalArgumentException e) {
            return;
        }

        String chatId = update.getMessage().getChatId().toString();
        switch (command) {
            case run:
                runGame(chatId, Games.user_of_the_day);
                break;
            case reg:
                addUserInGame(chatId, update.getMessage().getFrom());
                break;
            case delete:
                removeUserFromGame(chatId, update.getMessage().getFrom());
                break;
            case stats:
            case stat_user:
                sendStatisticOfTheGame(chatId,Games.user_of_the_day);
                break;
            case pidor:
                runGame(chatId,Games.loser_of_the_day);
                break;
            case pidorstats:
            case stat_pidor:
                sendStatisticOfTheGame(chatId,Games.loser_of_the_day);
                break;
            default:
                break;
        }
    }


    private void runGame(String chatId,Games game){
        // Per-chat lock: guarantees only one /run or /pidor for a given chat is in flight at a time.
        // Without this, two simultaneous commands could both pass the "is the same day" check,
        // pick different winners, double-increment counters, and emit two announcements.
        Object lock = CHAT_LOCKS.computeIfAbsent(chatId, k -> new Object());
        synchronized (lock) {
            DBHandler dbHandler = new DBHandler(config);
            try {
                List<UserForBD> usersInGame = dbHandler.getListOfPlayers(chatId);
                String[] messages;
                if (usersInGame.size() == 0) {
                    sendMsg(chatId,"Нет игроков");
                    return;
                }
                switch (game){
                    case user_of_the_day:
                        if (dbHandler.isTheSameDayRunning(chatId,getToday(), DBColumns.user_of_the_day_run_day)) {
                            sendMsg(chatId,messagesForUserOfTheDay[0] + dbHandler.getWinnerOfTheGame(chatId,Games.user_of_the_day));
                            return;
                        }
                        messages = messagesForUserOfTheDay;
                        break;
                    case loser_of_the_day:
                        if (dbHandler.isTheSameDayRunning(chatId,getToday(), DBColumns.loser_of_the_day_run_day)) {
                            sendMsg(chatId, messagesForLoserOfTheDay[0] + dbHandler.getWinnerOfTheGame(chatId,Games.loser_of_the_day));
                            return;
                        }
                        messages = messagesForLoserOfTheDay;
                        break;
                    default:
                        messages = null;
                }
                Timer timer = new Timer();
                int MESSAGE_DELAY = 1500;
                for(int  i = 1; i < messages.length; i++){
                    timer.schedule(new TimerSendingTask(chatId,messages[i]),MESSAGE_DELAY*i);
                }
                int i = RANDOM.nextInt(usersInGame.size());
                UserForBD winner = usersInGame.get(i);
                timer.schedule(new TimerSendingTask(chatId, messages[0] + winner.getNotificationName()),
                        MESSAGE_DELAY*messages.length);
                dbHandler.setWinnerAndDayRunning(chatId,winner,getToday(),game);
            } finally {
                dbHandler.closeConnection();
            }
        }
    }
    private void addUserInGame(String chatId, User user){
        DBHandler dbHandler = new DBHandler(config);
        try {
            dbHandler.registration(chatId, user);
        }catch (existedUserException e){
            sendMsg(chatId, "Ты уже в игре");
            return;
        } finally {
            dbHandler.closeConnection();
        }
        sendMsg(chatId, user.getFirstName() + ", Ты в игре");
    }

    private void removeUserFromGame(String chatId, User user){
        DBHandler dbHandler = new DBHandler(config);
        boolean removed;
        try {
            removed = dbHandler.removeRegistration(chatId, user);
        } finally {
            dbHandler.closeConnection();
        }

        if (removed) {
            sendMsg(chatId, user.getFirstName() + ", Ты вышел из игры");
        } else {
            sendMsg(chatId, "Тебя нет в игре");
        }
    }

    private void sendStatisticOfTheGame(String chatId,Games game) {
        String message = null;
        StringBuilder statisticUserOfTheDay = null;
        DBHandler dbHandler = new DBHandler(config);
        try {
            int i=1;
            List<UserForBD> users = dbHandler.getListOfPlayers(chatId);
            switch (game){
                case user_of_the_day:
                    message = "\uD83C\uDF89 Результаты Красавчик Дня\n";
                    statisticUserOfTheDay = new StringBuilder(message);
                    users.sort((left, right) -> Integer.compare(right.getUserDayCounter(), left.getUserDayCounter()));
                    for (UserForBD user : users) {
                        statisticUserOfTheDay.append(i++ + ")" + user.getNotificationName() +" - " +user.getUserDayCounter()  + " раз(а)\n");
                    }
                    break;
                case loser_of_the_day:
                    message = "Результаты игры \uD83C\uDF08Пидор Дня\n";
                    statisticUserOfTheDay = new StringBuilder(message);
                    users.sort((left, right) -> Integer.compare(right.getLoserDayCounter(), left.getLoserDayCounter()));
                    for (UserForBD user : users) {
                        statisticUserOfTheDay.append(i++ + ")" + user.getNotificationName() +" - " +user.getLoserDayCounter()  + " раз(а)\n");
                    }
                    break;
            }
        } finally {
            dbHandler.closeConnection();
        }
        sendMsg(chatId, statisticUserOfTheDay.toString());
    }

    //method for sending messages in the chat
    private synchronized void sendMsg(String chatId, String s) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.enableMarkdown(true);
        sendMessage.setChatId(chatId);
        sendMessage.setText(s);
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
    //method returns signed up username of bot
    public String getBotUsername() {
        return config.getBotUsername();
    }

    @Override
    public String getBotToken() {
        return config.getBotToken();
    }

    private long getToday(){
        // Use epoch-day (days since 1970-01-01) instead of dayOfYear (1..366):
        // strictly monotonic, no collision when the year rolls over.
        return LocalDate.now(ZoneId.of(config.getBotTimezone())).toEpochDay();
    }
}
