package com.honda.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;

/** Holds the global (session 0) system equalizer and remembers the band levels. */
public class EqManager {

    /** Returns the equalizer, or null if this unit does not support it. */
    public static synchronized Equalizer get(Context ctx) {
        if (ctx == null) return null;
        try {
            com.honda.launcher.audio.AutoEqManager autoEq = com.honda.launcher.audio.AutoEqManager.get(ctx);
            Equalizer e = autoEq.getEqualizer();
            if (e != null) {
                return e;
            }
        } catch (Throwable ignore) { }
        return null;
    }

    public static synchronized void setLevel(Context ctx, int band, int milliBel) {
        if (ctx == null) return;
        Context c = ctx.getApplicationContext();
        c.getSharedPreferences("eq", Context.MODE_PRIVATE)
                .edit().putInt("b" + band, milliBel).apply();
        Equalizer e = get(ctx);
        if (e != null) {
            try { e.setBandLevel((short) band, (short) milliBel); } catch (Throwable ignore) { }
        }
    }
}
