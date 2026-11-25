package dev.voxferrum;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;

final class Skin {
    final boolean tide;
    final int page, panel, plate, line, ink, muted, accent, action, actionInk;
    final Typeface display, mono = Typeface.MONOSPACE;

    Skin(boolean tide) {
        this.tide = tide;
        page = color(tide ? "#F4F1E9" : "#141211");
        panel = color(tide ? "#FFFCF4" : "#211C19");
        plate = color(tide ? "#E1EBE7" : "#3C2B21");
        line = color(tide ? "#A6B9AF" : "#735947");
        ink = color(tide ? "#102B35" : "#F6E8CF");
        muted = color(tide ? "#435C63" : "#CDBEAC");
        accent = color(tide ? "#105C69" : "#EAC382");
        action = color(tide ? "#0B5365" : "#972E25");
        actionInk = Color.WHITE;
        display = Typeface.create(tide ? "sans-serif-medium" : "serif", tide ? Typeface.NORMAL : Typeface.BOLD);
    }

    private static int color(String value) { return Color.parseColor(value); }

    GradientDrawable surface(int bg, int stroke, int radius, int px) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(px, stroke);
        return d;
    }

    RippleDrawable button(int bg, int stroke, int radius, int px) {
        return new RippleDrawable(ColorStateList.valueOf(tide ? 0x4424b9a4 : 0x44f9dca5),
                surface(bg, stroke, radius, px), null);
    }
}
