# TheUserOfTheDayBot

A Telegram bot that picks one «красавчик дня» (user of the day) and one «пидор
дня» (loser of the day) at random from registered chat members. One pick per
chat per day per game — calling the same command again the same day re-shows
the cached winner instead of re-rolling.

## Commands

| Command | What it does |
|---|---|
| `/reg` | Register yourself in the current chat |
| `/delete` | Leave the game in the current chat |
| `/run` | Pick today's «красавчик» |
| `/pidor` | Pick today's «пидор» |
| `/stats` | Leaderboard for `/run` |
| `/pidorstats` | Leaderboard for `/pidor` |

## Selection algorithm

* `java.security.SecureRandom` — uniform, no concerns about LCG bias or
  predictable seeding.
* Every registered player has probability `1/N`.
* The "one pick per day" gate is anchored to `BOT_TIMEZONE`
  (default `Europe/Moscow`).
* Per-chat in-memory lock: two simultaneous `/run` or `/pidor` calls in the
  same chat cannot both pick a winner — one runs, the other re-shows the
  result.
* Day stored as `LocalDate.toEpochDay()` (`BIGINT`), so no year-rollover
  collisions.

## Stack

* Java 8 (Eclipse Temurin)
* [`telegrambots`](https://github.com/rubenlagus/TelegramBots)
* MySQL 8 (schema auto-created on first start)
* Maven build, Docker Compose deployment

## Configuration

Required:

| Env var | Notes |
|---|---|
| `BOT_USERNAME` | bot's Telegram username, without `@` |
| `BOT_TOKEN` | from [@BotFather](https://t.me/BotFather) |
| `MYSQL_ROOT_PASSWORD` | MySQL root password |

Optional, with defaults:

| Env var | Default |
|---|---|
| `BOT_TIMEZONE` | `Europe/Moscow` |
| `DB_HOST` | `db` |
| `DB_PORT` | `3306` |
| `DB_NAME` | `chats_users_db` |
| `DB_USER` | `useroftheday` |
| `DB_PASSWORD` | `change_me_please` |
| `DB_CONNECT_RETRIES` | `20` |
| `DB_CONNECT_RETRY_DELAY_MS` | `3000` |

## Run

```bash
# create .env with at least BOT_USERNAME, BOT_TOKEN, MYSQL_ROOT_PASSWORD
docker compose up -d
docker compose logs -f bot
```

The schema is created by the bot on first connection (idempotent — safe to
restart).

## Migrating an existing deployment past `8b31502`

If your DB was created before this commit, the `chats.*_run_day` columns were
`INT` holding `dayOfYear` (1..366) which collides across years. Switch them to
`BIGINT` (epoch-day):

```sql
ALTER TABLE chats
  MODIFY user_of_the_day_run_day BIGINT NOT NULL DEFAULT 0,
  MODIFY loser_of_the_day_run_day BIGINT NOT NULL DEFAULT 0;

-- Optional: clear the cached winners so today's /run and /pidor re-roll.
UPDATE chats SET user_of_the_day = NULL, loser_of_the_day = NULL,
                 user_of_the_day_run_day = 0, loser_of_the_day_run_day = 0;
```

Fresh deployments don't need this — `initializeSchema()` already declares
`BIGINT`.
