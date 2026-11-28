package com.scriptprep.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Keeps preparing studio voices while the user is in another app: Android freezes an app soon
 * after it leaves the screen, so the work runs under a foreground service with a notification
 * that shows the progress and can stop it. The work itself stays in {@link StudioVoices}.
 */
public final class StudioService extends Service implements StudioVoices.Progress {
    private static final String CHANNEL = "studio_voices";
    private static final int PROGRESS_ID = 4711;
    private static final int RESULT_ID = 4712;
    private static final String ACTION_STOP = "com.scriptprep.app.STUDIO_STOP";
    private static final String TITLE = "title";

    private String title = "";

    /** Starts the service for a preparation that has just begun. */
    static void start(Context context, String title) {
        Intent intent = new Intent(context, StudioService.class).putExtra(TITLE, title);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException refused) {
            // The system may refuse; preparing still goes on while the app is open.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        StudioVoices studio = StudioVoices.get(this);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            studio.cancel();
            return START_NOT_STICKY;
        }
        // The scene being prepared now: one started later may only be waiting its turn.
        title = studio.currentTitle().isEmpty() && intent != null && intent.getStringExtra(TITLE) != null
            ? intent.getStringExtra(TITLE) : studio.currentTitle();
        createChannel();
        ServiceCompat.startForeground(this, PROGRESS_ID, progressNotification(studio.currentDone(), studio.currentTotal()),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        if (!studio.isRunning()) {
            stop();
            return START_NOT_STICKY;
        }
        studio.setObserver(this);
        return START_NOT_STICKY;
    }

    @Override
    public void progress(int done, int total) {
        title = StudioVoices.get(this).currentTitle();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(PROGRESS_ID, progressNotification(done, total));
        }
    }

    @Override
    public void finished(StudioVoices.Stop reason) {
        StudioVoices studio = StudioVoices.get(this);
        if (reason != StudioVoices.Stop.CANCELLED) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(RESULT_ID, resultNotification(reason));
            }
        }
        // Another scene waiting its turn has already started: keep going with it.
        if (studio.isRunning()) {
            title = studio.currentTitle();
            progress(studio.currentDone(), studio.currentTotal());
            return;
        }
        studio.setObserver(null);
        stop();
    }

    private void stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        StudioVoices.get(this).setObserver(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.studio_title),
                NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /** Opens the app where it was, like the launcher does. */
    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification progressNotification(int done, int total) {
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, StudioService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(getString(R.string.studio_notification_title))
            .setContentText(total > 0 ? title + " · " + getString(R.string.studio_ready, done, total) : title)
            .setProgress(Math.max(total, 0), done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.studio_stop), stop)
            .build();
    }

    private Notification resultNotification(StudioVoices.Stop reason) {
        String message = reason == StudioVoices.Stop.PARTIAL
            ? getString(R.string.studio_partial, StudioVoices.get(this).skippedLines()) : getString(switch (reason) {
            case DONE -> R.string.studio_notification_done;
            case DAILY_LIMIT -> R.string.studio_daily_limit;
            case MONTHLY_LIMIT -> R.string.studio_monthly_limit;
            case SCRIPT_LIMIT -> R.string.studio_script_limit;
            default -> R.string.studio_failed;
        });
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(getString(R.string.studio_title))
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build();
    }
}
