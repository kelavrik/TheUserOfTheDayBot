package com.UserOfTheDayBot;

import com.UserOfTheDayBot.enums.DBColumns;
import com.UserOfTheDayBot.enums.Games;
import org.telegram.telegrambots.meta.api.objects.User;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.UserOfTheDayBot.exceptions.existedUserException;

public class DBHandler {
    private Connection connection;
    public DBHandler() {
    }

    public DBHandler(AppConfig config) {
        connectToDB(
                config.getDbUrl(),
                config.getDbUser(),
                config.getDbPassword(),
                config.getDbConnectRetries(),
                config.getDbConnectRetryDelayMs()
        );
    }

    public void connectToDB(String url,String login,String password, int maxAttempts, long retryDelayMs){
        Properties properties = new Properties();
        properties.put("user", login);
        properties.put("password", password);
        properties.put("autoReconnect", "true");
        properties.put("characterEncoding", "UTF-8");
        properties.put("useUnicode", "true");
        properties.put("useSSL", "false");
        properties.put("useLegacyDatetimeCode", "false");
        properties.put("serverTimezone", "UTC");

        try {
            DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to register MySQL driver", e);
        }

        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                connection = DriverManager.getConnection(url, properties);
                initializeSchema();
                return;
            } catch (SQLException e) {
                if (attempt == attempts) {
                    throw new IllegalStateException("Unable to connect to DB after " + attempts + " attempt(s)", e);
                }

                System.out.println("DB is not ready yet, retry " + attempt + "/" + attempts);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("DB connection retry interrupted", interruptedException);
                }
            }
        }
    }
    public void closeConnection(){
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS users (" +
                            "user_id BIGINT PRIMARY KEY," +
                            "username VARCHAR(255)," +
                            "firstname VARCHAR(255)" +
                            ")"
            );
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS chats (" +
                            "chat_id BIGINT PRIMARY KEY," +
                            "user_of_the_day VARCHAR(255)," +
                            "loser_of_the_day VARCHAR(255)," +
                            // BIGINT to fit LocalDate.toEpochDay() (days since 1970-01-01).
                            // The previous INT held dayOfYear, which collides across years.
                            "user_of_the_day_run_day BIGINT NOT NULL DEFAULT 0," +
                            "loser_of_the_day_run_day BIGINT NOT NULL DEFAULT 0" +
                            ")"
            );
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS chat_user (" +
                            "chat_id BIGINT NOT NULL," +
                            "user_id BIGINT NOT NULL," +
                            "user_day_counter INT DEFAULT 0," +
                            "loser_counter INT DEFAULT 0," +
                            "PRIMARY KEY (chat_id, user_id)," +
                            "CONSTRAINT fk_chat_user_chat FOREIGN KEY (chat_id) REFERENCES chats(chat_id) ON DELETE CASCADE," +
                            "CONSTRAINT fk_chat_user_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE" +
                            ")"
            );
        }
    }

    public void registration(String chatId, User user)throws existedUserException{
        String query = "SELECT 1 FROM chat_user WHERE chat_id = ? AND user_id = ?";
        try(
                PreparedStatement selectStatement = connection.prepareStatement(query)
        ){
            selectStatement.setString(1, chatId);
            selectStatement.setLong(2, user.getId());
            if(selectStatement.executeQuery().next()){
                throw new existedUserException();
            }else {
                ensureChatExists(chatId);
                ensureUserExists(user);

                query = "INSERT INTO chat_user (chat_id, user_id) VALUES (?, ?)";
                try (PreparedStatement insertChatUser = connection.prepareStatement(query)) {
                    insertChatUser.setString(1, chatId);
                    insertChatUser.setLong(2, user.getId());
                    insertChatUser.executeUpdate();
                }
            }
        }catch (SQLException e){
            e.printStackTrace();
        }
    }

    public boolean removeRegistration(String chatId, User user){
        String query = "DELETE FROM chat_user WHERE chat_id = ? AND user_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, chatId);
            statement.setLong(2, user.getId());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
    public boolean isTheSameDayRunning(String chatId, long day, DBColumns column){
        String query = "SELECT " + column + " FROM chats WHERE chat_id = ?";
        try(PreparedStatement statement = connection.prepareStatement(query)){
            statement.setString(1, chatId);
            ResultSet result = statement.executeQuery();
            if(result.next()){
                return day == result.getLong(1);
            }

        }catch (SQLException e){
            e.printStackTrace();
        }
        return false;
    }
    public List<UserForBD> getListOfPlayers(String chatId){
        List<UserForBD> players = new ArrayList<UserForBD>();
        UserForBD user;
        // ORDER BY for deterministic position-to-user mapping. Doesn't affect uniformity of
        // RANDOM.nextInt(size) — that's uniform regardless of order — but makes the algorithm
        // reproducible (same seed + same DB state ⇒ same winner) and removes any latent dependency
        // on InnoDB's implementation-defined ordering.
        String query = "SELECT users.user_id, username, firstname, user_day_counter, loser_counter " +
                "FROM users JOIN chat_user ON chat_user.user_id = users.user_id " +
                "WHERE chat_user.chat_id = ? " +
                "ORDER BY users.user_id";
        try(PreparedStatement statement = connection.prepareStatement(query)){
            statement.setString(1, chatId);
            ResultSet usersFromBD = statement.executeQuery();
            while (usersFromBD.next()){
                user = createUserForBD(usersFromBD);
                user.setUserDayCounter(usersFromBD.getInt(4));
                user.setLoserDayCounter(usersFromBD.getInt(5));
                players.add(user);
            }
        }catch (SQLException e){
            e.printStackTrace();
        }
        return players;
    }
    private UserForBD createUserForBD(ResultSet resultSet)throws SQLException{
        return new UserForBD(resultSet.getLong(1),resultSet.getString(2),resultSet.getString(3));
    }
    public void setWinnerAndDayRunning(String chatId, UserForBD user, long dayRunning, Games column){
        String dayColumn = "";
        String counterColumn = "";
        switch (column){
            case user_of_the_day:
                dayColumn = "user_of_the_day_run_day";
                counterColumn = "user_day_counter";
                break;
            case loser_of_the_day:
                dayColumn = "loser_of_the_day_run_day";
                counterColumn = "loser_counter";
                break;
        }
        try(
                PreparedStatement updateChat = connection.prepareStatement(
                        "UPDATE chats SET " + column + " = ?, " + dayColumn + " = ? WHERE chat_id = ?"
                );
                PreparedStatement updateCounter = connection.prepareStatement(
                        "UPDATE chat_user SET " + counterColumn + " = " + counterColumn + " + 1 WHERE chat_id = ? AND user_id = ?"
                )
        ){
            updateChat.setString(1, user.getName());
            updateChat.setLong(2, dayRunning);
            updateChat.setString(3, chatId);
            updateChat.executeUpdate();

            updateCounter.setString(1, chatId);
            updateCounter.setLong(2, user.getID());
            updateCounter.executeUpdate();
        }catch (SQLException e){
            e.printStackTrace();
        }
    }
    public String getWinnerOfTheGame(String chatId,Games game){
        try(PreparedStatement statement = connection.prepareStatement("SELECT " + game + " FROM chats WHERE chat_id = ?")){
            statement.setString(1, chatId);
            ResultSet result = statement.executeQuery();
            if(result.next()){
                return result.getString(1);
            }
        }catch (SQLException e){
            e.printStackTrace();
        }
        return null;
    }

    /** Все chat_id, в которых бот хоть раз вёл игру — нужны для годовой церемонии. */
    public List<String> getAllChatIds() {
        List<String> ids = new ArrayList<String>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT chat_id FROM chats");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                ids.add(String.valueOf(rs.getLong(1)));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return ids;
    }

    /** Сбросить годовые счётчики и кэш сегодняшнего победителя для одного чата. */
    public void resetChatStats(String chatId) {
        try (PreparedStatement resetCounters = connection.prepareStatement(
                "UPDATE chat_user SET user_day_counter = 0, loser_counter = 0 WHERE chat_id = ?");
             PreparedStatement resetChat = connection.prepareStatement(
                "UPDATE chats SET user_of_the_day = NULL, loser_of_the_day = NULL, " +
                "user_of_the_day_run_day = 0, loser_of_the_day_run_day = 0 WHERE chat_id = ?")) {
            resetCounters.setString(1, chatId);
            resetCounters.executeUpdate();
            resetChat.setString(1, chatId);
            resetChat.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void ensureUserExists(User user) throws SQLException {
        try (PreparedStatement insertUser = connection.prepareStatement(
                "INSERT INTO users (user_id, username, firstname) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE username = VALUES(username), firstname = VALUES(firstname)"
        )) {
            insertUser.setLong(1, user.getId());
            insertUser.setString(2, user.getUserName());
            insertUser.setString(3, user.getFirstName());
            insertUser.executeUpdate();
        }
    }

    private void ensureChatExists(String chatId) throws SQLException {
        try (PreparedStatement insertChat = connection.prepareStatement(
                "INSERT INTO chats (chat_id) VALUES (?) ON DUPLICATE KEY UPDATE chat_id = VALUES(chat_id)"
        )) {
            insertChat.setString(1, chatId);
            insertChat.executeUpdate();
        }
    }
}
