package dev.voxferrum;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

final class TelegramUpload {
    static final long MAX_BYTES = 50_000_000L;

    static String send(Context context, TelegramAccount account, Uri uri, String filename,
                       Consumer<String> status) {
        try {
            InputStream source = context.getContentResolver().openInputStream(uri);
            if (source == null) throw new FileNotFoundException();
            try (DataInputStream wav = new DataInputStream(new BufferedInputStream(source, 65536))) {
                wav.mark(44);
                long pcmBytes = TelegramAudio.readHeader(wav);
                if (pcmBytes + 44 <= MAX_BYTES) {
                    wav.reset();
                    status.accept("Отправляем WAV в Telegram…");
                    return post(account, filename, "audio/wav", wav, pcmBytes + 44);
                }
                status.accept("Готовим копию M4A для Telegram…");
                return sendCompressed(context, account, filename, wav, pcmBytes, status);
            }
        } catch (FileNotFoundException | SecurityException unavailable) {
            return "Не удалось открыть выбранный клип. Проверьте, есть ли он в архиве.";
        } catch (IOException | RuntimeException failure) {
            return "Не удалось подготовить аудиофайл. Проверьте свободное место или отправьте WAV через «Поделиться».";
        }
    }

    private static String sendCompressed(Context context, TelegramAccount account, String name,
                                         DataInputStream pcm, long bytes, Consumer<String> status) throws IOException {
        File path = File.createTempFile("vox-send-", ".m4a", context.getCacheDir());
        try (RandomAccessFile copy = new RandomAccessFile(path, "rw")) {
            // Android keeps an unlinked file alive only while its descriptors are open.
            // Even process death during encoding/upload then frees this private copy.
            if (!path.delete()) throw new IOException("Cannot unlink temporary file");
            TelegramAudio.encode(pcm, bytes, copy.getFD(),
                    percent -> status.accept("Готовим копию M4A · " + percent + " %"));
            long length = copy.length();
            if (length <= 0 || length > MAX_BYTES) throw new IOException("Invalid M4A size");
            copy.seek(0);
            String m4a = name.replaceFirst("(?i)\\.wav$", "") + ".m4a";
            status.accept("Отправляем M4A в Telegram…");
            try (FileInputStream stream = new FileInputStream(copy.getFD())) {
                return post(account, m4a, "audio/mp4", stream, length);
            }
        } finally {
            if (path.exists()) path.delete();
        }
    }

    private static String post(TelegramAccount account, String filename, String mime,
                               InputStream in, long length) {
        String boundary = "vox-" + UUID.randomUUID();
        String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n"
                + account.chat + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\""
                + filename.replaceAll("[^a-zA-Z0-9_. -]", "_") + "\"\r\nContent-Type: " + mime + "\r\n\r\n";
        byte[] start = header.getBytes(StandardCharsets.UTF_8);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpsURLConnection connection = null;
        try {
            URL endpoint = new URL("https://api.telegram.org/bot" + account.token + "/sendDocument");
            connection = (HttpsURLConnection) endpoint.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(start.length + length + end.length);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(start);
                byte[] buffer = new byte[32768];
                long written = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    written += read;
                    if (written > length) throw new IOException("Unexpected audio length");
                    out.write(buffer, 0, read);
                }
                if (written != length) throw new IOException("Incomplete audio");
                out.write(end);
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] part = new char[2048];
                    int read;
                    while (response.length() < 65536 && (read = reader.read(part)) != -1) {
                        response.append(part, 0, read);
                    }
                }
                if (new JSONObject(response.toString()).optBoolean("ok")) return null;
            }
            switch (status) {
                case 400: return "Проверьте ID чата и откройте чат с ботом кнопкой «Старт».";
                case 401:
                case 404: return "Telegram отклонил токен. Проверьте его в настройках бота.";
                case 403: return "Боту запрещено писать в этот чат. Проверьте блокировку или права в группе.";
                case 413: return "Telegram отклонил размер файла. Выберите клип короче.";
                case 429: return "Telegram ограничил частоту запросов. Попробуйте позже.";
                default: return "Telegram не подтвердил доставку (HTTP " + status + "). Проверьте чат.";
            }
        } catch (Exception failure) {
            // Network exceptions may contain the full URL, which includes a bot credential.
            return "Не удалось подтвердить доставку. Проверьте сеть и чат перед повторной отправкой.";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
