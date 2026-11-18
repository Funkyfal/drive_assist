package com.geely.modehelper;

import android.car.Car;
import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A timeline of every readable car property, to find which ones change when
 * something happens (the car is switched off or on, a heater is toggled...).
 * Read-only towards the car. Writes files/props.log: first a BASE line with
 * the value of every property, then only CHG lines for changes, plus MARK lines
 * you add from adb. Stops by itself after the given minutes (max 30).
 *   adb pull /data/user/0/com.geely.modehelper/files/props.log ~/geely/
 */
final class PropWatch {
    static final String TAG = CarMode.TAG;

    private static final int KIND_INT = 1, KIND_FLOAT = 2, KIND_BOOL = 3, KIND_STRING = 4;
    private static final long PAUSE_MS = 2_000L;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int NOISY_AFTER = 40;
    private static final int MAX_MINUTES = 30;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static PropWatch instance;

    static synchronized String start(Context ctx, int minutes) {
        if (instance != null && instance.running) return "props: already running";
        int m = Math.max(1, Math.min(MAX_MINUTES, minutes));
        instance = new PropWatch(ctx.getApplicationContext(), m);
        instance.thread.start();
        return "props: started for " + m + " min";
    }

    static synchronized String stop() {
        if (instance == null || !instance.running) return "props: not running";
        instance.running = false;
        instance.thread.interrupt();
        return "props: stopping";
    }

    static synchronized String mark(String text) {
        if (instance == null || !instance.running) return "props: not running (mark ignored)";
        instance.write("MARK " + text);
        return "props: marked " + text;
    }

    private static final class Prop {
        final int id;
        final int kind;
        final int[] areas;
        Prop(int id, int kind, int[] areas) {
            this.id = id;
            this.kind = kind;
            this.areas = areas;
        }
        String label(int area) { return id + "(0x" + Integer.toHexString(id) + ")@" + area; }
    }

    private final Context app;
    private final int minutes;
    private final File file;
    private final Thread thread;
    private volatile boolean running = true;
    private Car car;
    private CarPropertyManager cpm;

    private PropWatch(Context app, int minutes) {
        this.app = app;
        this.minutes = minutes;
        this.file = new File(app.getFilesDir(), "props.log");
        this.file.delete();
        this.thread = new Thread(this::run, "props-watch");
        this.thread.setDaemon(true);
    }

    private void run() {
        EventLog.i("props: watching for " + minutes + " min, writing files/props.log");
        try {
            if (!connect()) { write("ERROR no car connection"); return; }
            List<Prop> props = loadProps();
            write("PROPS " + props.size() + " properties");

            Map<String, String> last = new HashMap<>();
            Map<String, Integer> changes = new HashMap<>();
            Set<String> dead = new HashSet<>();
            Set<String> muted = new HashSet<>();

            for (Prop p : props) {
                for (int a : p.areas) {
                    String k = p.label(a);
                    String v = read(p, a);
                    last.put(k, v);
                    if ("ERR".equals(v)) dead.add(k);
                    write("BASE " + k + " " + v);
                }
            }

            long end = SystemClock.elapsedRealtime() + minutes * 60_000L;
            while (running && SystemClock.elapsedRealtime() < end) {
                try { Thread.sleep(PAUSE_MS); } catch (InterruptedException e) { break; }
                for (Prop p : props) {
                    for (int a : p.areas) {
                        String k = p.label(a);
                        if (dead.contains(k) || muted.contains(k)) continue;
                        String v = read(p, a);
                        String old = last.get(k);
                        if (v.equals(old)) continue;
                        last.put(k, v);
                        write("CHG " + k + " " + old + " -> " + v);
                        Integer n = changes.get(k);
                        int count = n == null ? 1 : n + 1;
                        changes.put(k, count);
                        if (count > NOISY_AFTER) {
                            muted.add(k);
                            write("MUTED " + k + " (changes too often)");
                        }
                    }
                }
            }
            write("END");
        } catch (Throwable t) {
            Log.w(TAG, "props: " + t);
            write("ERROR " + t);
        } finally {
            running = false;
            try { if (car != null) car.disconnect(); } catch (Throwable ignored) { }
            car = null;
            cpm = null;
            EventLog.i("props: finished");
        }
    }

    private boolean connect() {
        try {
            car = Car.createCar(app, new ServiceConnection() {
                public void onServiceConnected(ComponentName n, IBinder b) { }
                public void onServiceDisconnected(ComponentName n) { cpm = null; }
            });
            car.connect();
            for (int i = 0; i < 60 && !car.isConnected(); i++) Thread.sleep(100);
            if (!car.isConnected()) return false;
            cpm = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            return cpm != null;
        } catch (Throwable t) {
            Log.w(TAG, "props: connect: " + t);
            return false;
        }
    }

    private List<Prop> loadProps() {
        List<Prop> out = new ArrayList<>();
        List<?> configs = cpm.getPropertyList();
        if (configs == null) return out;
        for (Object c : configs) {
            try {
                int id = (Integer) c.getClass().getMethod("getPropertyId").invoke(c);
                Class<?> type = (Class<?>) c.getClass().getMethod("getPropertyType").invoke(c);
                int[] areas = (int[]) c.getClass().getMethod("getAreaIds").invoke(c);
                if (areas == null || areas.length == 0) areas = new int[] {0};
                int kind = type == Integer.class ? KIND_INT
                        : type == Float.class ? KIND_FLOAT
                        : type == Boolean.class ? KIND_BOOL
                        : type == String.class ? KIND_STRING : 0;
                if (kind != 0) out.add(new Prop(id, kind, areas));
            } catch (Throwable ignored) { }
        }
        return out;
    }

    private String read(Prop p, int area) {
        try {
            switch (p.kind) {
                case KIND_INT: return String.valueOf(cpm.getIntProperty(p.id, area));
                case KIND_FLOAT: return String.valueOf(cpm.getFloatProperty(p.id, area));
                case KIND_BOOL: return String.valueOf(cpm.getBooleanProperty(p.id, area));
                case KIND_STRING: return stringValue(p.id, area);
                default: return "ERR";
            }
        } catch (Throwable t) {
            return "ERR";
        }
    }

    private String stringValue(int id, int area) throws Exception {
        Method m = cpm.getClass().getMethod("getProperty", Class.class, int.class, int.class);
        Object v = m.invoke(cpm, String.class, id, area);
        if (v == null) return "null";
        return String.valueOf(v.getClass().getMethod("getValue").invoke(v));
    }

    private synchronized void write(String line) {
        try {
            if (file.length() > MAX_BYTES) { running = false; return; }
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(FMT.format(new Date()) + " up=" + (SystemClock.elapsedRealtime() / 1000)
                        + "s " + line + "\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "props: file: " + e);
        }
    }
}