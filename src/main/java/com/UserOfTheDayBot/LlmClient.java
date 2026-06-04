package com.UserOfTheDayBot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Минимальный OpenRouter HTTP-клиент. Без внешних JSON-библиотек —
 * запрос лепится строкой, ответ парсится hand-rolled-парсером, потому что
 * нужное поле всего одно: {@code choices[0].message.content}.
 *
 * Используется только для генерации suspense-фраз в /run /pidor. Если что-то
 * пойдёт не так (нет ключа, таймаут, плохой JSON) — метод возвращает null,
 * и бот падает обратно к хардкод-фразам.
 */
final class LlmClient {

    private LlmClient() {}

    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";

    /**
     * Запрашивает у LLM ровно {@code wantedCount} коротких строк по {@code prompt}'у.
     * Возвращает массив длиной до {@code wantedCount} (после фильтрации пустых/коротких),
     * или null если ответ не получен.
     *
     * @param apiKey      ключ OpenRouter, если пустой — сразу null
     * @param model       slug модели (например "google/gemini-2.0-flash-001")
     * @param prompt      готовый промпт; в нём подразумевается формат «по одной фразе на строку»
     * @param wantedCount сколько строк взять
     * @param timeoutSec  таймаут на коннект и чтение в секундах
     */
    static String[] generateLines(String apiKey, String model, String prompt, int wantedCount, int timeoutSec) {
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            String body = "{"
                    + "\"model\":\"" + escapeJson(model) + "\","
                    + "\"temperature\":1.1,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escapeJson(prompt) + "\"}]"
                    + "}";
            URL url = new URL(API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("HTTP-Referer", "https://github.com/kelavrik/TheUserOfTheDayBot");
            conn.setRequestProperty("X-Title", "useroftheday-bot");

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = is == null ? "" : readAll(is);
            if (code < 200 || code >= 300) {
                System.err.println("[llm] HTTP " + code + ": " + truncate(responseBody, 200));
                return null;
            }

            String content = extractContent(responseBody);
            if (content == null) {
                System.err.println("[llm] couldn't extract content from: " + truncate(responseBody, 200));
                return null;
            }
            return splitLines(content, wantedCount);
        } catch (IOException e) {
            System.err.println("[llm] HTTP error: " + e.getMessage());
            return null;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Делим content на строки, чистим, берём первые wantedCount валидных. */
    private static String[] splitLines(String content, int wantedCount) {
        List<String> result = new ArrayList<String>();
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            // Срезаем нумерацию "1) ", "1. ", "- "
            line = line.replaceFirst("^[\\d]+[\\.)]\\s*", "");
            line = line.replaceFirst("^[-•*]\\s*", "");
            // Срезаем оборачивающие кавычки
            if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
                line = line.substring(1, line.length() - 1).trim();
            }
            if (line.isEmpty() || line.length() < 3) continue;
            result.add(line);
            if (result.size() >= wantedCount) break;
        }
        if (result.isEmpty()) return null;
        return result.toArray(new String[0]);
    }

    private static String readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Достаёт значение {@code "content":"..."} из JSON-ответа OpenRouter.
     * Не полноценный JSON-парсер: ищем подстроку, потом читаем экранированную
     * строку до неэкранированного {@code "}.
     */
    private static String extractContent(String json) {
        String marker = "\"content\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        int i = start + marker.length();
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                if (i + 1 >= json.length()) return null;
                char esc = json.charAt(i + 1);
                switch (esc) {
                    case 'n': sb.append('\n'); i += 2; break;
                    case 't': sb.append('\t'); i += 2; break;
                    case 'r': sb.append('\r'); i += 2; break;
                    case '"': sb.append('"'); i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case '/': sb.append('/'); i += 2; break;
                    case 'b': sb.append('\b'); i += 2; break;
                    case 'f': sb.append('\f'); i += 2; break;
                    case 'u':
                        if (i + 6 > json.length()) return null;
                        try {
                            int cp = Integer.parseInt(json.substring(i + 2, i + 6), 16);
                            sb.append((char) cp);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                        i += 6;
                        break;
                    default:
                        sb.append(esc);
                        i += 2;
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return null;
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
