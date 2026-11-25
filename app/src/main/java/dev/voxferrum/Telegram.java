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
        String help = Lang.text(R.string.create_a_bot_with_botfather_and_tap)
                + Lang.text(R.string.enter_the_recipient_chat_id_or_the);
        try {
            existing = TelegramAccount.load(activity);
        } catch (Exception unavailable) {
            help = Lang.text(R.string.could_not_read_saved_settings_enter_them);
        }
        final TelegramAccount saved = existing;
        VoxMenu menu = new VoxMenu(activity);
        android.content.Context themed = menu.getContext();
        LinearLayout form = new LinearLayout(themed);
        form.setOrientation(LinearLayout.VERTICAL);
        int spacing = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(spacing, spacing / 2, spacing, spacing / 2);
        TextView intro = new TextView(form.getContext());
        intro.setText(help);
        form.addView(intro);
        EditText token = field(activity, form, Lang.text(R.string.bot_token),
                saved == null ? Lang.text(R.string.token_from_botfather) : Lang.text(R.string.saved_enter_to_replace));
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        EditText chat = field(activity, form, Lang.text(R.string.recipient_chat), Lang.text(R.string.chat_id_or_channel));
        chat.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (saved != null) chat.setText(saved.chat);
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(form);

        AlertDialog.Builder builder = menu
                .setTitle(Lang.text(R.string.telegram_bot)).setView(scroll)
                .setPositiveButton(Lang.text(R.string.save_alt), null).setNegativeButton(Lang.text(R.string.cancel), null);
        if (isConfigured(activity)) {
            builder.setNeutralButton(Lang.text(R.string.disconnect), (d, w) -> {
                TelegramAccount.clear(activity);
                toast(activity, Lang.text(R.string.bot_settings_removed));
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
                token.setError(Lang.text(R.string.enter_the_complete_token_from_botfather));
                return;
            }
            String target = chat.getText().toString().trim();
            if (!target.matches("(?:-?[1-9][0-9]{0,18}|@[a-zA-Z][a-zA-Z0-9_]{3,31})")) {
                chat.setError(Lang.text(R.string.enter_a_numeric_chat_id_or_channel));
                return;
            }
            try {
                new TelegramAccount(value, target).save(activity);
            } catch (Exception error) {
                toast(activity, Lang.text(R.string.could_not_save_settings_on_this_phone));
                return;
            }
            dialog.dismiss();
            if (afterSave != null) afterSave.run();
            else toast(activity, Lang.text(R.string.bot_settings_saved));
        });
    }

    private static EditText field(Activity activity, LinearLayout form, String label, String hint) {
        TextView title = new TextView(form.getContext());
        title.setText(label);
        int top = Math.round(12 * activity.getResources().getDisplayMetrics().density);
        title.setPadding(0, top, 0, 0);
        form.addView(title);
        EditText input = new EditText(form.getContext());
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
                ? Lang.text(R.string.n_nan_m4a_copy_will_be_sent)
                : "";
        new VoxMenu(activity).setTitle(Lang.text(R.string.send_clip))
                .setMessage(filename + Lang.text(R.string.n_ntelegram_chat) + recipient.chat + format)
                .setPositiveButton(Lang.text(R.string.send), (d, w) -> upload(activity, recipient, clip, filename))
                .setNegativeButton(Lang.text(R.string.cancel), null).show();
    }

    private static void upload(Activity activity, TelegramAccount account, Uri clip, String filename) {
        Context context = activity.getApplicationContext();
        if (!sending.compareAndSet(false, true)) {
            showProgress(activity);
            return;
        }
        transferStatus = Lang.text(R.string.opening_clip);
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
            String message = result == null ? Lang.text(R.string.clip_delivered_to_chat) + account.chat : result;
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
            return new VoxMenu(getActivity()).setTitle(Lang.text(R.string.send_to_telegram))
                    .setMessage(transferStatus)
                    .setNegativeButton(Lang.text(R.string.minimize), null).create();
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
