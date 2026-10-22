package com.honda.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;

/** Holds the global (session 0) system equalizer and remembers the band levels. */
public class EqManager {
    private static Equalizer eq;

    /** Returns the equalizer, or null if this unit does not support it. */
    public static synchronized Equalizer get(Context ctx) {
        if (eq == null) {
            try {
                Context c = ctx.getApplicationContext();
                eq = new Equalizer(0, 0);
                eq.setEnabled(true);
                SharedPreferences sp = c.getSharedPreferences("eq", Context.MODE_PRIVATE);
                short n = eq.getNumberOfBands();
                for (short i = 0; i < n; i++) {
                    if (sp.contains("b" + i)) eq.setBandLevel(i, (short) sp.getInt("b" + i, 0));
                }
            } catch (Throwable t) {
                eq = null;
            }
        }
        return eq;
    }

    public static synchronized void setLevel(Context ctx, int band, int milliBel) {
        Equalizer e = get(ctx);
        if (e == null) return;
        try { e.setBandLevel((short) band, (short) milliBel); } catch (Throwable ignore) { }
        ctx.getApplicationContext().getSharedPreferences("eq", Context.MODE_PRIVATE)
                .edit().putInt("b" + band, milliBel).apply();
    }
}
