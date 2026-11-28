package com.scriptprep.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Keeps a rehearsal going while the user is in another app or the screen is off: partner lines
 * keep playing and, with hands-free on, the microphone keeps listening. Android freezes an app that
 * leaves the screen unless a foreground service runs, so one runs while the rehearsal screen is
 * open, with a notification that can stop the rehearsal.
 */
public final class RehearsalService extends Service {
    private static final String CHANNEL = "rehearsal";
    private static final int ID = 4721;
    private static final String ACTION_STOP = "com.scriptprep.app.REHEARSAL_STOP";
    private static final String TITLE = "title";

    /** Set by the activity: stops the rehearsal when the notification's Stop is tapped. */
    static volatile Runnable onStop;
    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    /** Starts it while the app is on screen, as Android lets the microphone be used from there. */
    static void start(Context context, String title) {
        try {
            Intent intent = new Intent(context, RehearsalService.class).putExtra(TITLE, title);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException refused) {
            // Without it the rehearsal still runs while the app is on screen.
        }
    }

    static void stop(Context context) {
        if (running) {
            context.stopService(new Intent(context, RehearsalService.class));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Runnable stop = onStop;
            if (stop != null) {
                stop.run();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        createChannel();
        String title = intent == null ? "" : intent.getStringExtra(TITLE);
        int types = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            // The microphone type needs the permission already granted.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
        }
        try {
            ServiceCompat.startForeground(this, ID, notification(title == null ? "" : title), types);
            running = true;
        } catch (RuntimeException refused) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.rehearsal_notification_channel), NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification notification(String title) {
        Intent open = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, RehearsalService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.rehearsal_notification_title))
            .setContentText(title)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tap)
            .addAction(0, getString(R.string.rehearsal_notification_stop), stop)
            .build();
    }
}
