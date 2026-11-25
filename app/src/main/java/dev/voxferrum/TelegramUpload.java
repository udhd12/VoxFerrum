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
                    status.accept(Lang.text(R.string.sending_wav_to_telegram));
                    return post(account, filename, "audio/wav", wav, pcmBytes + 44);
                }
                status.accept(Lang.text(R.string.preparing_an_m4a_copy_for_telegram));
                return sendCompressed(context, account, filename, wav, pcmBytes, status);
            }
        } catch (FileNotFoundException | SecurityException unavailable) {
            return Lang.text(R.string.could_not_open_this_clip_check_the);
        } catch (IOException | RuntimeException failure) {
            return Lang.text(R.string.could_not_prepare_audio_check_storage_or);
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
                    percent -> status.accept(Lang.text(R.string.preparing_m4a_copy) + percent + " %"));
            long length = copy.length();
            if (length <= 0 || length > MAX_BYTES) throw new IOException("Invalid M4A size");
            copy.seek(0);
            String m4a = name.replaceFirst("(?i)\\.wav$", "") + ".m4a";
            status.accept(Lang.text(R.string.sending_m4a_to_telegram));
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
                case 400: return Lang.text(R.string.check_the_chat_id_and_tap_start);
                case 401:
                case 404: return Lang.text(R.string.telegram_rejected_the_token_check_bot_settings);
                case 403: return Lang.text(R.string.the_bot_cannot_post_in_this_chat);
                case 413: return Lang.text(R.string.telegram_rejected_the_file_size_choose_a);
                case 429: return Lang.text(R.string.telegram_is_rate_limiting_requests_try_again);
                default: return Lang.text(R.string.telegram_did_not_confirm_delivery_http) + status + Lang.text(R.string.check_the_chat);
            }
        } catch (Exception failure) {
            // Network exceptions may contain the full URL, which includes a bot credential.
            return Lang.text(R.string.delivery_unconfirmed_check_your_connection_and_chat);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
