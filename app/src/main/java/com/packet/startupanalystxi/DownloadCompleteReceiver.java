package com.packet.startupanalystxi;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.HashSet;
import java.util.Set;

public class DownloadCompleteReceiver extends BroadcastReceiver {
    private static final String DL_CHANNEL_ID = "downloads_channel";
    private static final String PREFS = "downloads_prefs";
    private static final String KEY_PENDING = "pending_ids";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (id == -1) return;

        // Only notify for downloads we initiated (persisted IDs)
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY_PENDING, new HashSet<>()));
        String key = Long.toString(id);
        if (!set.contains(key)) return;
        set.remove(key);
        sp.edit().putStringSet(KEY_PENDING, set).apply();

        createChannel(context);

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                String title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                String mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    Uri fileUri = dm.getUriForDownloadedFile(id);
                    String locationText = "Downloads/" + (title != null ? title : "file");
                    showNotification(context, title != null ? title : "File downloaded", locationText, fileUri, mime);
                } else {
                    showNotification(context, "Download failed", title != null ? title : "File", null, null);
                }
            }
        }
    }

    private void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    DL_CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Download status and completion");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void showNotification(Context ctx, String title, String location, Uri fileUri, String mimeType) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, DL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(location)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (fileUri != null) {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(fileUri, (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(ctx, (int) (System.currentTimeMillis() & 0xffff), viewIntent, flags);
            builder.setContentIntent(pi);
        }

        NotificationManagerCompat.from(ctx).notify((int) (System.currentTimeMillis() & 0x7fffffff), builder.build());
    }
}