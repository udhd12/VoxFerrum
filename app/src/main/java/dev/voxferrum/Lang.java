package dev.voxferrum;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import java.util.Locale;

public final class Lang extends Application {
    private static volatile Context localized;

    @Override public void onCreate() {
        super.onCreate();
        configure(this, getSharedPreferences("vox", MODE_PRIVATE).getBoolean("english", false));
    }

    private static void configure(Context context, boolean english) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(english ? Locale.ENGLISH : new Locale("ru"));
        localized = context.getApplicationContext().createConfigurationContext(config);
    }

    static Locale locale() { return localized.getResources().getConfiguration().getLocales().get(0); }

    static boolean english() {
        return "en".equals(locale().getLanguage());
    }

    static String text(int id) { return localized.getString(id); }

    static void select(Context context, boolean english) {
        configure(context, english);
        context.getSharedPreferences("vox", MODE_PRIVATE).edit().putBoolean("english", english).apply();
        ReplayWidget.refresh(context);
        context.sendBroadcast(new Intent(ReplayService.CHANGED).setPackage(context.getPackageName()));
    }
}
