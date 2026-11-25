package dev.voxferrum;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class VoxMenu extends AlertDialog.Builder {
    private final Skin skin;
    private AlertDialog dialog;

    VoxMenu(Context context) {
        super(context, context.getSharedPreferences("vox", 0).getBoolean("tide", false)
                ? R.style.TideDialog : R.style.ForgeDialog);
        skin = new Skin(context.getSharedPreferences("vox", 0).getBoolean("tide", false));
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    @Override public VoxMenu setTitle(CharSequence title) {
        TextView heading = new TextView(getContext());
        heading.setText(title);
        heading.setTextSize(22);
        heading.setTypeface(skin.display);
        heading.setTextColor(skin.accent);
        heading.setPadding(dp(22), dp(20), dp(22), dp(14));
        setCustomTitle(heading);
        return this;
    }

    @Override public VoxMenu setItems(CharSequence[] items, DialogInterface.OnClickListener action) {
        return setSingleChoiceItems(items, -1, action);
    }

    @Override public VoxMenu setSingleChoiceItems(CharSequence[] items, int selected, DialogInterface.OnClickListener action) {
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(10));
        scroll.addView(list);
        for (int i = 0; i < items.length; i++) {
            int index = i;
            boolean active = i == selected;
            android.widget.Button row = new android.widget.Button(getContext());
            row.setAllCaps(false);
            row.setText(active ? "✓   " + items[i] : items[i]);
            row.setSelected(active);
            row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            row.setTextColor(active ? skin.accent : skin.ink);
            row.setTextSize(17);
            row.setMinHeight(dp(50));
            row.setPadding(dp(16), dp(6), dp(16), dp(6));
            row.setBackground(skin.button(active ? skin.plate : skin.panel, active ? skin.accent : skin.line,
                    dp(skin.tide ? 14 : 3), dp(1)));
            row.setOnClickListener(v -> { dialog.dismiss(); action.onClick(dialog, index); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(5);
            list.addView(row, lp);
        }
        setView(scroll);
        return this;
    }

    @Override public AlertDialog create() {
        dialog = super.create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new InsetDrawable(
                    skin.surface(skin.panel, skin.line, dp(skin.tide ? 20 : 4), dp(1)), dp(12)));
        return dialog;
    }
}
