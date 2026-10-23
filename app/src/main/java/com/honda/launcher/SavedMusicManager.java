package com.honda.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FilenameFilter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Manages saving music played on the car via mobile Bluetooth to car internal LCD memory (storage),
 * and provides built-in offline playback so songs can be listened to in the future even without a phone.
 */
public class SavedMusicManager {

    public static class Track {
        public final File file;
        public final String title;
        public final String artist;
        public final long sizeBytes;
        public final long lastModified;

        public Track(File file, String title, String artist, long sizeBytes, long lastModified) {
            this.file = file;
            this.title = title;
            this.artist = artist;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
        }

        public String getDisplayName() {
            if (artist != null && !artist.trim().isEmpty() && !title.contains(artist)) {
                return title + " - " + artist;
            }
            return title;
        }

        public String getSizeString() {
            float mb = sizeBytes / (1024f * 1024f);
            return String.format(Locale.US, "%.1f MB", mb);
        }
    }

    private static SavedMusicManager instance;
    private final Context context;

    private MediaRecorder recorder;
    private boolean isRecording = false;
    private String currentRecTitle;
    private String currentRecArtist;
    private File currentRecFile;
    private long recStartTime = 0;

    private MediaPlayer player;
    private Track currentlyPlaying;
    private int currentTrackIndex = -1;
    private List<Track> playlist = new ArrayList<>();
    private Runnable playbackCallback;

    public static synchronized SavedMusicManager get(Context context) {
        if (instance == null) {
            instance = new SavedMusicManager(context.getApplicationContext());
        }
        return instance;
    }

    private SavedMusicManager(Context context) {
        this.context = context;
        ensureDirectory();
    }

    public File getStorageDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "DashPilot_Music");
        if (!dir.exists()) dir.mkdirs();
        if (!dir.canWrite()) {
            dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Recorded");
            if (!dir.exists()) dir.mkdirs();
        }
        return dir;
    }

    private void ensureDirectory() {
        try {
            File d = getStorageDir();
            if (!d.exists()) d.mkdirs();
        } catch (Throwable ignore) { }
    }

    public synchronized List<Track> getSavedTracks() {
        List<Track> list = new ArrayList<>();
        File dir = getStorageDir();
        if (!dir.exists() || !dir.isDirectory()) return list;

        File[] files = dir.listFiles(new FilenameFilter() {
            @Override public boolean accept(File d, String name) {
                String n = name.toLowerCase(Locale.US);
                return n.endsWith(".m4a") || n.endsWith(".mp3") || n.endsWith(".aac") || n.endsWith(".wav");
            }
        });

        if (files != null) {
            for (File f : files) {
                if (f.length() < 10000) continue; // Skip tiny corrupted stubs
                String rawName = f.getName();
                int dot = rawName.lastIndexOf('.');
                if (dot > 0) rawName = rawName.substring(0, dot);

                String title = rawName;
                String artist = "";
                if (rawName.contains(" - ")) {
                    String[] parts = rawName.split(" - ", 2);
                    title = parts[0].trim();
                    artist = parts[1].trim();
                }
                list.add(new Track(f, title, artist, f.length(), f.lastModified()));
            }
        }

        // Sort by newest first
        Collections.sort(list, new Comparator<Track>() {
            @Override public int compare(Track o1, Track o2) {
                return Long.compare(o2.lastModified, o1.lastModified);
            }
        });
        playlist = list;
        return list;
    }

    // ---------------------------- Download / Recording Logic ----------------------------

    public synchronized boolean isTrackDownloaded(String trackTitle, String artist) {
        if (trackTitle == null || trackTitle.trim().isEmpty()) return false;
        String name = (artist != null && !artist.trim().isEmpty() && !trackTitle.contains(artist))
                ? trackTitle.trim() + " - " + artist.trim() : trackTitle.trim();
        name = sanitizeFilename(name);
        File dir = getStorageDir();
        File f1 = new File(dir, name + ".m4a");
        if (f1.exists() && f1.length() > 30000) return true;
        File f2 = new File(dir, sanitizeFilename(trackTitle.trim()) + ".m4a");
        return f2.exists() && f2.length() > 30000;
    }

    public synchronized boolean isDownloading() {
        return isRecording;
    }

    public synchronized String getDownloadingTitle() {
        return currentRecTitle;
    }

    public synchronized boolean startDownload(String title, String artist) {
        if (title == null || title.trim().isEmpty()) return false;
        if (isRecording) {
            stopRecording();
        }
        startRecording(title.trim(), (artist != null) ? artist.trim() : "");
        return isRecording;
    }

    public synchronized void stopDownload() {
        stopRecording();
    }

    public synchronized void onTrackPlaying(String trackTitle, String artist, boolean autoSaveEnabled) {
        // Deprecated: replaced by on-demand download button on music control bar
    }

    public static String sanitizeFilename(String raw) {
        if (raw == null) return "Unknown_Track";
        String s = raw.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        s = s.replaceAll("^_+|_+$", "").trim();
        return s.isEmpty() ? "Unknown_Track" : s;
    }

    private synchronized void startRecording(String title, String artist) {
        try {
            File dir = getStorageDir();
            String name = (artist != null && !artist.isEmpty() && !title.contains(artist)) ? title + " - " + artist : title;
            name = sanitizeFilename(name);
            File outFile = new File(dir, name + ".m4a");

            recorder = new MediaRecorder();
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            } catch (Throwable t) {
                recorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
            }
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(192000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            isRecording = true;
            currentRecTitle = title;
            currentRecArtist = artist;
            currentRecFile = outFile;
            recStartTime = System.currentTimeMillis();
        } catch (Throwable e) {
            isRecording = false;
            releaseRecorder();
        }
    }

    public synchronized void stopRecording() {
        if (!isRecording && recorder == null) return;
        isRecording = false;
        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (Throwable ignore) { }
        finally {
            releaseRecorder();
        }

        // If recording was less than 5 seconds or smaller than 35KB, delete stub
        if (currentRecFile != null && currentRecFile.exists()) {
            long duration = System.currentTimeMillis() - recStartTime;
            if (duration < 5000 || currentRecFile.length() < 35000) {
                try { currentRecFile.delete(); } catch (Throwable ignore) { }
            }
        }
        currentRecFile = null;
        currentRecTitle = null;
        currentRecArtist = null;
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.reset();
                recorder.release();
            } catch (Throwable ignore) { }
            recorder = null;
        }
    }

    public boolean isRecording() { return isRecording; }
    public String getCurrentRecordingTitle() { return currentRecTitle; }

    // ---------------------------- Offline Playback ----------------------------

    public synchronized void playTrack(final Track track, Runnable onStateChanged) {
        this.playbackCallback = onStateChanged;
        stopRecording(); // Don't record own playback
        stopPlayback();

        try {
            player = new MediaPlayer();
            player.setDataSource(track.file.getAbsolutePath());
            player.prepare();
            player.start();
            currentlyPlaying = track;

            // Find index in playlist
            List<Track> list = getSavedTracks();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).file.getAbsolutePath().equals(track.file.getAbsolutePath())) {
                    currentTrackIndex = i;
                    break;
                }
            }

            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    playNext();
                }
            });

            if (playbackCallback != null) playbackCallback.run();
        } catch (Throwable e) {
            Toast.makeText(context, "Cannot play: " + track.title, Toast.LENGTH_SHORT).show();
            currentlyPlaying = null;
        }
    }

    public synchronized void playNext() {
        List<Track> list = getSavedTracks();
        if (list.isEmpty()) return;
        currentTrackIndex = (currentTrackIndex + 1) % list.size();
        playTrack(list.get(currentTrackIndex), playbackCallback);
    }

    public synchronized void playPrevious() {
        List<Track> list = getSavedTracks();
        if (list.isEmpty()) return;
        currentTrackIndex = (currentTrackIndex - 1 + list.size()) % list.size();
        playTrack(list.get(currentTrackIndex), playbackCallback);
    }

    public synchronized void togglePlayPause() {
        if (player == null) {
            List<Track> list = getSavedTracks();
            if (!list.isEmpty()) {
                playTrack(list.get(0), playbackCallback);
            }
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.start();
            }
            if (playbackCallback != null) playbackCallback.run();
        } catch (Throwable ignore) { }
    }

    public synchronized void stopPlayback() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Throwable ignore) { }
            player = null;
        }
        currentlyPlaying = null;
        if (playbackCallback != null) playbackCallback.run();
    }

    public synchronized boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public Track getCurrentlyPlaying() { return currentlyPlaying; }

    // ---------------------------- UI Dialog ----------------------------

    public void showLibraryDialog(final Activity activity, final Runnable onPlayTriggered) {
        final List<Track> tracks = getSavedTracks();
        final int dp = (int) (activity.getResources().getDisplayMetrics().density);

        final AlertDialog dialog = new AlertDialog.Builder(activity).create();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF00A0E17);
        root.setPadding(dp * 16, dp * 14, dp * 16, dp * 14);

        // Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView tTitle = new TextView(activity);
        tTitle.setText("CAR MEMORY MUSIC");
        tTitle.setTextSize(18);
        tTitle.setTextColor(0xFFFFFFFF);
        tTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        titleCol.addView(tTitle);

        TextView tSub = new TextView(activity);
        long freeBytes = 0;
        try {
            StatFs stat = new StatFs(getStorageDir().getAbsolutePath());
            freeBytes = stat.getAvailableBytes();
        } catch (Throwable ignore) { }
        float freeGb = freeBytes / (1024f * 1024f * 1024f);

        tSub.setText(String.format(Locale.US, "%d Saved Songs • %.1f GB Free Storage", tracks.size(), freeGb));
        tSub.setTextSize(12);
        tSub.setTextColor(0xFF00E676);
        titleCol.addView(tSub);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnClose = new TextView(activity);
        btnClose.setText("✕ CLOSE");
        btnClose.setTextSize(13);
        btnClose.setTextColor(0xFFCFD8DC);
        btnClose.setTypeface(Typeface.DEFAULT_BOLD);
        btnClose.setPadding(dp * 12, dp * 6, dp * 12, dp * 6);
        btnClose.setBackground(pillDrawable(0x22FFFFFF));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        header.addView(btnClose);
        root.addView(header);

        View div = new View(activity);
        div.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp * 1);
        dlp.topMargin = dp * 10;
        dlp.bottomMargin = dp * 10;
        root.addView(div, dlp);

        if (tracks.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("No saved songs found in car memory yet.\n\nTurn on 'SAVE PLAYED MUSIC TO CAR' in settings, then play any song from your phone via Bluetooth — DashPilot will automatically record and save it here for offline listening!");
            empty.setTextSize(13);
            empty.setTextColor(0x8CFFFFFF);
            empty.setPadding(dp * 10, dp * 20, dp * 10, dp * 20);
            empty.setGravity(Gravity.CENTER);
            root.addView(empty);
        } else {
            ScrollView sv = new ScrollView(activity);
            sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp * 280));

            final LinearLayout listLayout = new LinearLayout(activity);
            listLayout.setOrientation(LinearLayout.VERTICAL);

            for (final Track track : tracks) {
                final LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp * 10, dp * 8, dp * 10, dp * 8);
                row.setBackground(pillDrawable(0x14FFFFFF));

                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp * 6;
                row.setLayoutParams(rlp);

                LinearLayout info = new LinearLayout(activity);
                info.setOrientation(LinearLayout.VERTICAL);

                TextView trackTitle = new TextView(activity);
                trackTitle.setText(track.title);
                trackTitle.setTextSize(14);
                trackTitle.setTextColor(0xFFFFFFFF);
                trackTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                info.addView(trackTitle);

                TextView trackSub = new TextView(activity);
                String subInfo = (track.artist != null && !track.artist.isEmpty())
                        ? track.artist + " • " + track.getSizeString() : track.getSizeString();
                trackSub.setText(subInfo);
                trackSub.setTextSize(11);
                trackSub.setTextColor(0x8CFFFFFF);
                info.addView(trackSub);

                row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                // Play Button
                TextView btnPlay = new TextView(activity);
                btnPlay.setText("▶ PLAY");
                btnPlay.setTextSize(12);
                btnPlay.setTextColor(0xFF00E676);
                btnPlay.setTypeface(Typeface.DEFAULT_BOLD);
                btnPlay.setPadding(dp * 10, dp * 6, dp * 10, dp * 6);
                btnPlay.setBackground(pillDrawable(0x2200E676));
                btnPlay.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        playTrack(track, onPlayTriggered);
                        Toast.makeText(activity, "Playing: " + track.title, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                });
                row.addView(btnPlay);

                // Delete Button
                TextView btnDel = new TextView(activity);
                btnDel.setText("🗑");
                btnDel.setTextSize(14);
                btnDel.setTextColor(0xFFFF5252);
                btnDel.setPadding(dp * 8, dp * 6, dp * 8, dp * 6);
                LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp2.leftMargin = dp * 6;
                btnDel.setLayoutParams(dlp2);
                btnDel.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            if (track.file.delete()) {
                                listLayout.removeView(row);
                                Toast.makeText(activity, "Deleted from car memory", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable ignore) { }
                    }
                });
                row.addView(btnDel);

                listLayout.addView(row);
            }
            sv.addView(listLayout);
            root.addView(sv);
        }

        dialog.setView(root);
        dialog.show();
    }

    private static GradientDrawable pillDrawable(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(14);
        return g;
    }
}
