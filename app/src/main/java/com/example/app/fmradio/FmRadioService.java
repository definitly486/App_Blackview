package com.example.app.fmradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.example.app.MainActivity;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Blackview/Unisoc FM service.
 *
 * RadioManager/RadioTuner are hidden framework APIs on normal Android SDKs,
 * so they must NOT be imported directly. The OEM FM application uses these
 * APIs too, but this implementation accesses them reflectively so the APK
 * can be compiled with the public Android SDK.
 */
public class FmRadioService extends Service {
    public static final String ACTION_POWER = "com.example.app.fmradio.POWER";
    public static final String ACTION_TUNE = "com.example.app.fmradio.TUNE";
    public static final String ACTION_SEEK_UP = "com.example.app.fmradio.SEEK_UP";
    public static final String ACTION_SEEK_DOWN = "com.example.app.fmradio.SEEK_DOWN";
    public static final String ACTION_SCAN = "com.example.app.fmradio.SCAN";
    public static final String ACTION_STATE = "com.example.app.fmradio.STATE";
    public static final String EXTRA_FREQUENCY = "frequency";

    private static final String CHANNEL_ID = "fm_radio_playback";
    private static final int NOTIFICATION_ID = 7101;
    private static final int MIN_FREQ = 8750;
    private static final int MAX_FREQ = 10800;
    private static final int STATUS_OK = 0;
    private static final int DIRECTION_UP = 0;
    private static final int DIRECTION_DOWN = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Object radioManager;
    private Object tuner;
    private Object tunerCallback;
    private int frequency = 8750;
    private boolean powered;
    private boolean seeking;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private final Runnable refreshAfterSeek = new Runnable() {
        @Override public void run() {
            refreshFrequencyFromTuner();
            seeking = false;
            broadcast(null);
            updateNotification();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppBlackview:FMRadio");
            wakeLock.setReferenceCounted(false);
        }
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_POWER.equals(action)) togglePower();
            else if (ACTION_TUNE.equals(action)) {
                tune(clamp(intent.getIntExtra(EXTRA_FREQUENCY, frequency)));
            } else if (ACTION_SEEK_UP.equals(action)) {
                seek(true);
            } else if (ACTION_SEEK_DOWN.equals(action)) {
                seek(false);
            } else if (ACTION_SCAN.equals(action)) {
                scan();
            }
        }
        return START_STICKY;
    }

    public void togglePower() {
        if (powered) powerDown(); else powerUp();
    }

    private boolean openTuner() {
        if (tuner != null) return true;
        try {
            Class<?> radioManagerClass = Class.forName("android.hardware.radio.RadioManager");
            Object manager = getSystemService("broadcastradio");
            if (manager == null) {
                // Some OEM builds expose the service under the framework constant
                // but do not expose Context.RADIO_SERVICE in the public SDK.
                Method getService = Context.class.getMethod("getSystemService", String.class);
                manager = getService.invoke(this, "broadcastradio");
            }
            if (manager == null || !radioManagerClass.isInstance(manager)) {
                broadcast("FM: системный Broadcastradio service не найден");
                return false;
            }
            radioManager = manager;

            Class<?> modulePropertiesClass = Class.forName("android.hardware.radio.RadioManager$ModuleProperties");
            List<Object> modules = new ArrayList<>();
            Method listModules = radioManagerClass.getMethod("listModules", List.class);
            Object result = listModules.invoke(radioManager, modules);
            if (!(result instanceof Integer) || ((Integer) result) != STATUS_OK || modules.isEmpty()) {
                broadcast("FM-тюнер не найден");
                return false;
            }

            Object module = modules.get(0);
            Method getId = modulePropertiesClass.getMethod("getId");
            int id = ((Number) getId.invoke(module)).intValue();

            // openTuner(int moduleId, BandConfig config, boolean withAudio,
            //           RadioTuner.Callback callback, Handler handler)
            Class<?> tunerClass = Class.forName("android.hardware.radio.RadioTuner");
            Class<?> callbackClass = Class.forName("android.hardware.radio.RadioTuner$Callback");

            // The OEM FM app supplies a real RadioTuner.Callback. Passing null
            // works on some builds but throws inside the framework on others.
            // A dynamic proxy lets us provide a callback without compiling
            // against the hidden RadioTuner API.
            tunerCallback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("onError".equals(name)) {
                            int status = (args != null && args.length > 0 && args[0] instanceof Number)
                                    ? ((Number) args[0]).intValue() : -1;
                            broadcast("FM ошибка: " + status);
                        } else if ("onAntennaState".equals(name)) {
                            boolean connected = args != null && args.length > 0 && Boolean.TRUE.equals(args[0]);
                            broadcast(connected ? "Антенна подключена" : "Антенна не обнаружена");
                        } else if ("onProgramInfoChanged".equals(name)) {
                            if (args != null && args.length > 0 && args[0] != null) {
                                try {
                                    Method getChannel = args[0].getClass().getMethod("getChannel");
                                    Object ch = getChannel.invoke(args[0]);
                                    if (ch instanceof Number) frequency = clamp(((Number) ch).intValue());
                                } catch (Throwable ignored) { }
                            }
                            broadcast(null);
                            updateNotification();
                        }
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == byte.class) return (byte) 0;
                        if (method.getReturnType() == short.class) return (short) 0;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        if (method.getReturnType() == float.class) return 0f;
                        if (method.getReturnType() == double.class) return 0d;
                        if (method.getReturnType() == char.class) return (char) 0;
                        return null;
                    });

            Method openTuner = radioManagerClass.getMethod(
                    "openTuner", int.class,
                    Class.forName("android.hardware.radio.RadioManager$BandConfig"),
                    boolean.class,
                    callbackClass,
                    Handler.class);
            tuner = openTuner.invoke(radioManager, id, null, true, tunerCallback, null);
            if (tuner == null || !tunerClass.isInstance(tuner)) {
                tuner = null;
                broadcast("FM: openTuner() не открыл тюнер");
                return false;
            }
            return true;
        } catch (Throwable t) {
            tuner = null;
            tunerCallback = null;
            radioManager = null;
            Throwable cause = t;
            if (t instanceof InvocationTargetException && ((InvocationTargetException) t).getCause() != null) {
                cause = ((InvocationTargetException) t).getCause();
            }
            android.util.Log.e("AppBlackviewFM", "openTuner failed", cause);
            broadcast("FM: " + cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()));
            return false;
        }
    }

    private void closeTuner() {
        if (tuner == null) return;
        try {
            tuner.getClass().getMethod("close").invoke(tuner);
        } catch (Throwable ignored) {
        }
        tuner = null;
        radioManager = null;
    }

    public void powerUp() {
        if (!openTuner()) {
            powered = false;
            updateNotification();
            return;
        }

        requestAudioFocus();
        setVendorFmParameter("AudioFmPreStop=0");

        int result = invokeInt("tune", new Class<?>[]{int.class, int.class}, frequency, 0);
        powered = result == STATUS_OK;
        if (powered) {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            muteFmAudio(false);
            broadcast("Включено");
        } else {
            abandonAudioFocus();
            setVendorFmParameter("AudioFmPreStop=1");
            broadcast("Не удалось включить FM: " + result);
        }
        updateNotification();
    }

    public void powerDown() {
        powered = false;
        seeking = false;
        handler.removeCallbacks(refreshAfterSeek);
        muteFmAudio(true);
        setVendorFmParameter("AudioFmPreStop=1");
        abandonAudioFocus();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        closeTuner();
        broadcast("Выключено");
        updateNotification();
    }

    public void tune(int f) {
        frequency = clamp(f);
        if (!powered || tuner == null) {
            broadcast(null);
            updateNotification();
            return;
        }
        int result = invokeInt("tune", new Class<?>[]{int.class, int.class}, frequency, 0);
        refreshFrequencyFromTuner();
        broadcast(result == STATUS_OK ? null : "Ошибка настройки: " + result);
        updateNotification();
    }

    public void seek(boolean up) {
        if (!powered || tuner == null || seeking) return;
        seeking = true;
        broadcast("Поиск станции…");
        int result = invokeInt("scan", new Class<?>[]{int.class, boolean.class},
                up ? DIRECTION_UP : DIRECTION_DOWN, true);
        if (result != STATUS_OK) {
            seeking = false;
            broadcast("SEEK: " + result);
            updateNotification();
            return;
        }
        handler.removeCallbacks(refreshAfterSeek);
        handler.postDelayed(refreshAfterSeek, 1200L);
    }

    public void scan() {
        if (!powered || tuner == null || seeking) return;
        seeking = true;
        broadcast("Сканирование…");
        // OEM implementation starts the dynamic program list. We invoke it
        // reflectively when available; the tuner remains powered meanwhile.
        try {
            Method method = tuner.getClass().getMethod("startProgramListUpdates", Class.forName("android.hardware.radio.ProgramList$Filter"));
            method.invoke(tuner, new Object[]{null});
        } catch (Throwable ignored) {
            // Fallback: scan upward repeatedly, keeping the last found station.
            invokeInt("scan", new Class<?>[]{int.class, boolean.class}, DIRECTION_UP, true);
        }
        handler.removeCallbacks(refreshAfterSeek);
        handler.postDelayed(refreshAfterSeek, 1800L);
    }

    private void refreshFrequencyFromTuner() {
        if (tuner == null) return;
        try {
            Method getInfo = tuner.getClass().getMethod("getProgramInformation");
            Object info = getInfo.invoke(tuner);
            if (info != null) {
                Method getChannel = info.getClass().getMethod("getChannel");
                Object ch = getChannel.invoke(info);
                if (ch instanceof Number) frequency = clamp(((Number) ch).intValue());
            }
        } catch (Throwable ignored) {
        }
    }

    private int invokeInt(String name, Class<?>[] parameterTypes, Object... args) {
        if (tuner == null) return -1;
        try {
            Object result = tuner.getClass().getMethod(name, parameterTypes).invoke(tuner, args);
            return result instanceof Number ? ((Number) result).intValue() : STATUS_OK;
        } catch (Throwable t) {
            return -1;
        }
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focus -> {
                        if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            muteFmAudio(true);
                        } else if (focus == AudioManager.AUDIOFOCUS_GAIN && powered) {
                            muteFmAudio(false);
                        }
                    }).build();
            audioManager.requestAudioFocus(audioFocusRequest);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null || audioFocusRequest == null || Build.VERSION.SDK_INT < 26) return;
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        audioFocusRequest = null;
    }

    private void muteFmAudio(boolean mute) {
        if (audioManager == null) return;
        try {
            int volume = mute ? 0 : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            audioManager.setParameters("FM_Volume=" + volume);
        } catch (Throwable ignored) {
        }
    }

    private void setVendorFmParameter(String parameter) {
        if (audioManager == null) return;
        try { audioManager.setParameters(parameter); } catch (Throwable ignored) {}
    }

    private int clamp(int f) {
        return Math.max(MIN_FREQ, Math.min(MAX_FREQ, f));
    }

    private void broadcast(String status) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_FREQUENCY, frequency);
        i.putExtra("powered", powered);
        i.putExtra("seeking", seeking);
        if (status != null) i.putExtra("status", status);
        sendBroadcast(i);
    }

    private PendingIntent command(String action) {
        Intent i = new Intent(this, FmRadioService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("FM Radio Blackview")
                .setContentText(String.format(Locale.US, "FM %.1f MHz", frequency / 100.0))
                .setContentIntent(content)
                .setOngoing(powered)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "SEEK −", command(ACTION_SEEK_DOWN)).build())
                .addAction(new Notification.Action.Builder(
                        powered ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        powered ? "Выкл" : "Вкл", command(ACTION_POWER)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "SEEK +", command(ACTION_SEEK_UP)).build());
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "FM Radio", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Фоновая работа аппаратного FM-тюнера Blackview");
            nm.createNotificationChannel(c);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (powered) startForeground(NOTIFICATION_ID, buildNotification());
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        powerDown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
