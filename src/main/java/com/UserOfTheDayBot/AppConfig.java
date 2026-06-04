package com.UserOfTheDayBot;

public class AppConfig {
    private final String botUsername;
    private final String botToken;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String botTimezone;
    private final int dbConnectRetries;
    private final long dbConnectRetryDelayMs;
    private final String openrouterApiKey;
    private final String openrouterModel;

    private AppConfig(
            String botUsername,
            String botToken,
            String dbUrl,
            String dbUser,
            String dbPassword,
            String botTimezone,
            int dbConnectRetries,
            long dbConnectRetryDelayMs,
            String openrouterApiKey,
            String openrouterModel
    ) {
        this.botUsername = botUsername;
        this.botToken = botToken;
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.botTimezone = botTimezone;
        this.dbConnectRetries = dbConnectRetries;
        this.dbConnectRetryDelayMs = dbConnectRetryDelayMs;
        this.openrouterApiKey = openrouterApiKey;
        this.openrouterModel = openrouterModel;
    }

    public static AppConfig fromEnv() {
        String dbUrl = readEnv("DB_URL");
        if (isBlank(dbUrl)) {
            String dbHost = readEnvOrDefault("DB_HOST", "db");
            String dbPort = readEnvOrDefault("DB_PORT", "3306");
            String dbName = readEnvOrDefault("DB_NAME", "chats_users_db");
            dbUrl = "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName;
        }

        return new AppConfig(
                requireEnv("BOT_USERNAME"),
                requireEnv("BOT_TOKEN"),
                dbUrl,
                readEnvOrDefault("DB_USER", "root"),
                readEnvOrDefault("DB_PASSWORD", "root"),
                readEnvOrDefault("BOT_TIMEZONE", "Europe/Moscow"),
                parseInt(readEnvOrDefault("DB_CONNECT_RETRIES", "15"), 15),
                parseLong(readEnvOrDefault("DB_CONNECT_RETRY_DELAY_MS", "3000"), 3000L),
                // Optional: если ключа нет — LLM-генерация suspense просто
                // отключается и бот падает обратно к хардкод-строкам.
                readEnvOrDefault("OPENROUTER_API_KEY", ""),
                readEnvOrDefault("OPENROUTER_MODEL", "google/gemini-2.5-flash-lite")
        );
    }

    public String getBotUsername() {
        return botUsername;
    }

    public String getBotToken() {
        return botToken;
    }

    public String getDbUrl() {
        return dbUrl;
    }

    public String getDbUser() {
        return dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public String getBotTimezone() {
        return botTimezone;
    }

    public int getDbConnectRetries() {
        return dbConnectRetries;
    }

    public long getDbConnectRetryDelayMs() {
        return dbConnectRetryDelayMs;
    }

    public String getOpenrouterApiKey() {
        return openrouterApiKey;
    }

    public String getOpenrouterModel() {
        return openrouterModel;
    }

    private static String requireEnv(String name) {
        String value = readEnv(name);
        if (isBlank(value)) {
            throw new IllegalStateException("Environment variable '" + name + "' is required");
        }
        return value;
    }

    private static String readEnvOrDefault(String name, String defaultValue) {
        String value = readEnv(name);
        return isBlank(value) ? defaultValue : value;
    }

    private static String readEnv(String name) {
        return System.getenv(name);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
