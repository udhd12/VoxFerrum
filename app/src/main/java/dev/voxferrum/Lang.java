package dev.voxferrum;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;

public final class Lang extends Application {
    private static volatile Context localized;

    @Override public void onCreate() {
        super.onCreate();
        configure(this, getResources().getConfiguration(), choice(this));
    }

    static int choice(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("vox", MODE_PRIVATE);
        if (!prefs.contains("english")) return 0;
        return prefs.getBoolean("english", false) ? 2 : 1;
    }

    private static Locale systemLocale(LocaleList preferred) {
        for (int i = 0; i < preferred.size(); i++) {
            Locale locale = preferred.get(i);
            if ("ru".equals(locale.getLanguage()) || "en".equals(locale.getLanguage())) return locale;
        }
        return Locale.ENGLISH;
    }

    private static void configure(Context context, Configuration configuration, int mode) {
        Configuration config = new Configuration(configuration);
        config.setLocale(mode == 0 ? systemLocale(config.getLocales()) :
                mode == 1 ? new Locale("ru") : Locale.ENGLISH);
        localized = context.getApplicationContext().createConfigurationContext(config);
    }

    static Locale locale() { return localized.getResources().getConfiguration().getLocales().get(0); }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        Locale previous = locale();
        configure(this, config, choice(this));
        if (!previous.equals(locale())) refresh(this);
    }

    static String text(int id) { return localized.getString(id); }

    static void select(Context context, int mode) {
        SharedPreferences.Editor prefs = context.getSharedPreferences("vox", MODE_PRIVATE).edit();
        if (mode == 0) prefs.remove("english"); else prefs.putBoolean("english", mode == 2);
        configure(context, context.getResources().getConfiguration(), mode);
        prefs.apply();
        refresh(context);
    }

    private static void refresh(Context context) {
        ReplayWidget.refresh(context);
        context.sendBroadcast(new Intent(ReplayService.CHANGED).setPackage(context.getPackageName()));
    }
}
