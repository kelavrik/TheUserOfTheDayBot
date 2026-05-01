# TheUserOfTheDayBot

Телеграм-бот, который раз в сутки случайно выбирает «красавчика дня» и
«пидора дня» из зарегистрированных участников чата. Один розыгрыш на чат
в день для каждой игры — повторный вызов команды в тот же день покажет
победителя.

## Команды

| Команда | Что делает |
|---|---|
| `/reg` | Зарегистрироваться в розыгрыше в текущем чате |
| `/delete` | Выйти из розыгрыша |
| `/run` | Выбрать «красавчика дня» |
| `/pidor` | Выбрать «пидора дня» |
| `/stats` | Турнирная таблица для `/run` |
| `/pidorstats` | Турнирная таблица для `/pidor` |

## Алгоритм выбора

* `java.security.SecureRandom` — равномерное распределение, никаких претензий
  к LCG-смещению или предсказуемости семени.
* У каждого зарегистрированного игрока вероятность ровно `1/N`.
* «Один розыгрыш в сутки» считается по `BOT_TIMEZONE` (по умолчанию
  `Europe/Moscow`).
* Лок per-chat (`ConcurrentHashMap<String, Object>`): два одновременных
  `/run` или `/pidor` в одном чате не могут оба выбрать победителя — один
  розыгрывает, второй показывает результат первого.
* День хранится как `LocalDate.toEpochDay()` (`BIGINT`) — никаких коллизий
  при переходе через год.

## Стек

* Java 8 (Eclipse Temurin)
* [`telegrambots`](https://github.com/rubenlagus/TelegramBots)
* MySQL 8 (схема создаётся автоматически при первом старте)
* Maven для сборки, Docker Compose для деплоя

## Конфигурация

Обязательные:

| Переменная | Описание |
|---|---|
| `BOT_USERNAME` | username бота в Telegram, без `@` |
| `BOT_TOKEN` | токен от [@BotFather](https://t.me/BotFather) |
| `MYSQL_ROOT_PASSWORD` | пароль root для MySQL |

Опциональные, со значениями по умолчанию:

| Переменная | Default |
|---|---|
| `BOT_TIMEZONE` | `Europe/Moscow` |
| `DB_HOST` | `db` |
| `DB_PORT` | `3306` |
| `DB_NAME` | `chats_users_db` |
| `DB_USER` | `useroftheday` |
| `DB_PASSWORD` | `change_me_please` |
| `DB_CONNECT_RETRIES` | `20` |
| `DB_CONNECT_RETRY_DELAY_MS` | `3000` |

## Запуск

```bash
# создай .env как минимум с BOT_USERNAME, BOT_TOKEN, MYSQL_ROOT_PASSWORD
docker compose up -d
docker compose logs -f bot
```

Схема создаётся ботом при первом подключении к БД. Перезапуски
идемпотентны — данные не теряются.

## Миграция существующего деплоя на `8b31502` и новее

Если БД создавалась до этого коммита, колонки `chats.user_of_the_day_run_day`
и `chats.loser_of_the_day_run_day` были `INT` и хранили `dayOfYear` (1..366),
который коллизит на каждом переходе через год. В новой версии — `BIGINT` с
epoch-day:

```sql
ALTER TABLE chats
  MODIFY user_of_the_day_run_day BIGINT NOT NULL DEFAULT 0,
  MODIFY loser_of_the_day_run_day BIGINT NOT NULL DEFAULT 0;

-- по желанию: сбросить кэш сегодняшних победителей,
-- чтобы ближайший /run и /pidor сделали свежий выбор.
UPDATE chats SET user_of_the_day = NULL, loser_of_the_day = NULL,
                 user_of_the_day_run_day = 0, loser_of_the_day_run_day = 0;
```

Чистым деплоям эта миграция не нужна — `initializeSchema()` сразу создаёт
колонки `BIGINT`.
