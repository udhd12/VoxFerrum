package dev.voxferrum;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Telegram {
    private Telegram() {}
    private static final AtomicBoolean sending = new AtomicBoolean();
    private static volatile String transferStatus;
    private static final String PROGRESS_TAG = "telegram_transfer";

    public static boolean isConfigured(Context context) {
        return TelegramAccount.exists(context);
    }

    public static void showSettings(Activity activity) {
        settings(activity, null);
    }

    private static void settings(Activity activity, Runnable afterSave) {
        TelegramAccount existing = null;
        String help = "Создайте бота у @BotFather и нажмите «Старт» в чате с ним. "
                + "Укажите числовой ID получателя или @имя канала, где бот может публиковать.";
        try {
            existing = TelegramAccount.load(activity);
        } catch (Exception unavailable) {
            help = "Не удалось прочитать сохранённые настройки. Введите их заново.";
        }
        final TelegramAccount saved = existing;
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int spacing = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(spacing, spacing / 2, spacing, spacing / 2);
        TextView intro = new TextView(activity);
        intro.setText(help);
        form.addView(intro);
        EditText token = field(activity, form, "Токен бота",
                saved == null ? "Токен от @BotFather" : "Сохранён · введите для замены");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        EditText chat = field(activity, form, "Чат получателя", "ID чата или @имя канала");
        chat.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (saved != null) chat.setText(saved.chat);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Бот Telegram").setView(scroll)
                .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null);
        if (isConfigured(activity)) {
            builder.setNeutralButton("Отключить", (d, w) -> {
                TelegramAccount.clear(activity);
                toast(activity, "Настройки бота удалены");
            });
        }
        AlertDialog dialog = builder.create();
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = token.getText().toString().trim();
            if (value.isEmpty() && saved != null) value = saved.token;
            if (!value.matches("[0-9]{1,20}:[A-Za-z0-9_-]{16,200}")) {
                token.setError("Вставьте целиком токен от @BotFather");
                return;
            }
            String target = chat.getText().toString().trim();
            if (!target.matches("(?:-?[1-9][0-9]{0,18}|@[a-zA-Z][a-zA-Z0-9_]{3,31})")) {
                chat.setError("Введите числовой ID чата или @имя канала");
                return;
            }
            try {
                new TelegramAccount(value, target).save(activity);
            } catch (Exception error) {
                toast(activity, "Не удалось сохранить настройки на телефоне");
                return;
            }
            dialog.dismiss();
            if (afterSave != null) afterSave.run();
            else toast(activity, "Настройки бота сохранены");
        });
    }

    private static EditText field(Activity activity, LinearLayout form, String label, String hint) {
        TextView title = new TextView(activity);
        title.setText(label);
        int top = Math.round(12 * activity.getResources().getDisplayMetrics().density);
        title.setPadding(0, top, 0, 0);
        form.addView(title);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        title.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setHint(hint);
        form.addView(input);
        return input;
    }

    public static void send(Activity activity, Uri clip, String filename, long bytes) {
        TelegramAccount account;
        try {
            account = TelegramAccount.load(activity);
        } catch (Exception unavailable) {
            account = null;
        }
        if (account == null) {
            settings(activity, () -> send(activity, clip, filename, bytes));
            return;
        }
        final TelegramAccount recipient = account;
        String format = bytes > TelegramUpload.MAX_BYTES
                ? "\n\nДля отправки будет создана копия M4A. Исходный WAV останется в архиве."
                : "";
        new AlertDialog.Builder(activity).setTitle("Отправить клип?")
                .setMessage(filename + "\n\nЧат Telegram: " + recipient.chat + format)
                .setPositiveButton("Отправить", (d, w) -> upload(activity, recipient, clip, filename))
                .setNegativeButton("Отмена", null).show();
    }

    private static void upload(Activity activity, TelegramAccount account, Uri clip, String filename) {
        Context context = activity.getApplicationContext();
        if (!sending.compareAndSet(false, true)) {
            showProgress(activity);
            return;
        }
        transferStatus = "Открываем клип…";
        showProgress(activity);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String result;
            try {
                result = TelegramUpload.send(context, account, clip, filename,
                        status -> transferStatus = status);
            } finally {
                sending.set(false);
            }
            String message = result == null ? "Клип доставлен в чат " + account.chat : result;
            main.post(() -> toast(context, message));
        }, "vox-telegram").start();
    }

    private static void showProgress(Activity activity) {
        if (activity.getFragmentManager().findFragmentByTag(PROGRESS_TAG) == null) {
            new TransferDialog().show(activity.getFragmentManager(), PROGRESS_TAG);
        }
    }

    public static final class TransferDialog extends DialogFragment {
        private final Handler clock = new Handler(Looper.getMainLooper());
        private final Runnable redraw = new Runnable() {
            @Override public void run() {
                if (!sending.get()) {
                    dismissAllowingStateLoss();
                    return;
                }
                AlertDialog dialog = (AlertDialog) getDialog();
                if (dialog != null) dialog.setMessage(transferStatus);
                clock.postDelayed(this, 450);
            }
        };

        @Override public Dialog onCreateDialog(Bundle state) {
            return new AlertDialog.Builder(getActivity()).setTitle("Отправка в Telegram")
                    .setMessage(transferStatus)
                    .setNegativeButton("Свернуть", null).create();
        }

        @Override public void onStart() {
            super.onStart();
            clock.post(redraw);
        }

        @Override public void onStop() {
            clock.removeCallbacks(redraw);
            super.onStop();
        }
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
