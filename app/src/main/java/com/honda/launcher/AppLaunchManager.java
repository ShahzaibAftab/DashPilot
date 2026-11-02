package com.honda.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, lightweight application launch and navigation manager for DashPilot.
 *
 * Tailored for low-memory automotive head units (e.g., Allwinner T3, 1GB RAM, Android 7.1.1):
 * 1. Rapid-click load debounce: Prevents multi-tap intent flooding and system window thrashing.
 * 2. Zero-allocation prioritization check: Minimal GC pressure.
 * 3. Intelligent memory-friendly task reuse flags: FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.
 * 4. Graceful multi-tier fallback: Native Lightweight App -> Generic Intent -> Mobile Web PWA.
 */
public final class AppLaunchManager {

    /** Debounce window in milliseconds to protect low-RAM hardware from rapid button spamming */
    public static final long DEBOUNCE_THRESHOLD_MS = 1000L;

    /**
     * Prioritized Maps applications.
     * Prioritizes ultra-light Google Maps Go before full Google Maps or offline alternatives.
     */
    public static final String[] PKG_MAPS = {
            "com.google.android.apps.mapslite", // Google Maps Go (PWA container, <5MB)
            "com.google.android.apps.navlite",  // Navigation for Maps Go
            "com.google.android.apps.maps",     // Full Google Maps
            "net.osmand.plus",                  // OsmAnd+ (Offline GPS)
            "net.osmand",                       // OsmAnd (Offline GPS)
            "com.waze",                         // Waze
            "com.here.app.maps",                // HERE WeGo
            "com.sygic.aura",                   // Sygic
            "com.autonavi.amapauto",            // AutoNavi
            "com.baidu.BaiduMap"                // Baidu Maps
    };

    /**
     * Prioritized YouTube applications.
     * Prioritizes NewPipe and lightweight ad-free clients (35MB RAM, no Google Play Services needed)
     * over heavy official YouTube (300MB+ RAM).
     */
    public static final String[] PKG_YOUTUBE = {
            "org.schabi.newpipe",                   // NewPipe (Ultra-lightweight, 0 ads, 1080p, <50MB RAM)
            "org.schabi.newpipe.legacy",            // NewPipe Legacy (Android 7.1)
            "com.google.android.apps.youtube.mango",// YouTube Go
            "com.teamsmart.videomanager.tv",        // SmartTube
            "com.liskovsoft.videomanager",          // SmartTube Next
            "app.revanced.android.youtube",         // YouTube ReVanced
            "com.vanced.android.youtube",           // YouTube Vanced
            "com.google.android.youtube",           // Official YouTube
            "com.syu.video",                        // Head-unit stock video player
            "com.car.video",
            "com.ts.video"
    };

    public static final String URL_MAPS_WEB = "https://www.google.com/maps";
    public static final String URL_YOUTUBE_WEB = "https://m.youtube.com";
    public static final String URI_GEO_GENERIC = "geo:0,0?q=";

    private static final AtomicLong lastLaunchTimeMs = new AtomicLong(0);

    private AppLaunchManager() {
        // Utility class, no instantiation
    }

    /**
     * Thread-safe check and update for launch debouncing.
     * Protects low-spec head units against rapid button spamming or duplicate touch events.
     *
     * @param currentTimeMs Current timestamp in milliseconds
     * @return true if launch is allowed; false if debounced/suppressed
     */
    public static boolean checkAndDebounce(long currentTimeMs) {
        while (true) {
            long last = lastLaunchTimeMs.get();
            if (currentTimeMs - last < DEBOUNCE_THRESHOLD_MS && currentTimeMs >= last) {
                return false;
            }
            if (lastLaunchTimeMs.compareAndSet(last, currentTimeMs)) {
                return true;
            }
        }
    }

    /** Reset debounce state (primarily for automated testing) */
    public static void resetDebounce() {
        lastLaunchTimeMs.set(0);
    }

    /**
     * Abstraction for package resolution to allow unit testing on JVM without Android device mocks.
     */
    public interface PackageResolver {
        Intent getLaunchIntent(String packageName);
    }

    /**
     * Standard Android PackageManager resolver.
     */
    public static class AndroidPackageResolver implements PackageResolver {
        private final PackageManager pm;

        public AndroidPackageResolver(PackageManager pm) {
            this.pm = pm;
        }

        @Override
        public Intent getLaunchIntent(String packageName) {
            if (pm == null || packageName == null) return null;
            try {
                Intent i = pm.getLaunchIntentForPackage(packageName);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                }
                return i;
            } catch (Throwable ignore) {
                return null;
            }
        }
    }

    /**
     * Resolves which package should be launched for Maps, or null if none installed.
     */
    public static String resolveMapsPackage(PackageResolver resolver) {
        if (resolver == null) return null;
        for (String pkg : PKG_MAPS) {
            Intent i = resolver.getLaunchIntent(pkg);
            if (i != null) return pkg;
        }
        return null;
    }

    /**
     * Resolves which package should be launched for YouTube, or null if none installed.
     */
    public static String resolveYouTubePackage(PackageResolver resolver) {
        if (resolver == null) return null;
        for (String pkg : PKG_YOUTUBE) {
            Intent i = resolver.getLaunchIntent(pkg);
            if (i != null) return pkg;
        }
        return null;
    }

    /**
     * Resolves the highest-priority Maps intent available, or returns fallback.
     */
    public static Intent resolveMapsIntent(PackageResolver resolver) {
        if (resolver != null) {
            for (String pkg : PKG_MAPS) {
                Intent i = resolver.getLaunchIntent(pkg);
                if (i != null) return i;
            }
        }
        // Fallback to web PWA
        return createWebFallbackIntent(URL_MAPS_WEB);
    }

    /**
     * Resolves the highest-priority YouTube intent available, or returns fallback.
     */
    public static Intent resolveYouTubeIntent(PackageResolver resolver) {
        if (resolver != null) {
            for (String pkg : PKG_YOUTUBE) {
                Intent i = resolver.getLaunchIntent(pkg);
                if (i != null) return i;
            }
        }
        // Fallback to lightweight mobile web
        return createWebFallbackIntent(URL_YOUTUBE_WEB);
    }

    /**
     * Creates a lightweight browser fallback intent with low-memory task flags.
     */
    public static Intent createWebFallbackIntent(String url) {
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return web;
    }

    /**
     * Creates a generic geo navigation intent.
     */
    public static Intent createGeoIntent() {
        Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse(URI_GEO_GENERIC));
        geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return geo;
    }

    /**
     * Performs a safe, debounced launch of Maps.
     */
    public static boolean launchMaps(Context context) {
        if (context == null) return false;
        if (!checkAndDebounce(System.currentTimeMillis())) {
            return false;
        }

        PackageResolver resolver = new AndroidPackageResolver(context.getPackageManager());

        // 1. Try prioritized installed packages (Maps Go first)
        for (String pkg : PKG_MAPS) {
            Intent i = resolver.getLaunchIntent(pkg);
            if (i != null) {
                try {
                    context.startActivity(i);
                    return true;
                } catch (Throwable ignore) { }
            }
        }

        // 2. Try generic geo navigation intent
        try {
            Intent geo = createGeoIntent();
            context.startActivity(geo);
            return true;
        } catch (Throwable ignore) { }

        // 3. Fallback to lightweight Google Maps web
        try {
            Intent web = createWebFallbackIntent(URL_MAPS_WEB);
            context.startActivity(web);
            return true;
        } catch (Throwable ignore) { }

        return false;
    }

    /**
     * Performs a safe, debounced launch of YouTube.
     */
    public static boolean launchYouTube(Context context) {
        if (context == null) return false;
        if (!checkAndDebounce(System.currentTimeMillis())) {
            return false;
        }

        PackageResolver resolver = new AndroidPackageResolver(context.getPackageManager());

        // 1. Try prioritized installed packages (NewPipe / YouTube Go / SmartTube first)
        for (String pkg : PKG_YOUTUBE) {
            Intent i = resolver.getLaunchIntent(pkg);
            if (i != null) {
                try {
                    context.startActivity(i);
                    return true;
                } catch (Throwable ignore) { }
            }
        }

        // 2. Fallback to mobile YouTube web
        try {
            Intent web = createWebFallbackIntent(URL_YOUTUBE_WEB);
            context.startActivity(web);
            return true;
        } catch (Throwable ignore) { }

        return false;
    }
}
