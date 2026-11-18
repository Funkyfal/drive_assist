package com.geely.modehelper;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

// System helper (uid system), no UI. Runs continuously to:
// 1) keep the car connection and the power-state listener alive
//    (a resume wakes ModeMemory so it can restore the saved modes);
// 2) compile the helper after each install (Dexopt);
// 3) answer command-line Bluetooth pairing (BT_PAIR).
// The modes themselves live in ModeMemory, the range in RangeTracker,
// Bluetooth in BluetoothKeeper. Wi-Fi is no longer forced on.
public class ModeHelperService extends Service {
    static final String TAG = "ModeHelper";
    /** Signature permission guarding every entry point reachable from outside. */
    static final String PERMISSION = "com.geely.modehelper.permission.CONTROL";
    public static final String ACTION_BT_PAIR = "com.geely.modehelper.svc.BT_PAIR";
    /** A pairing request is only answered for this long after BT_PAIR. */
    static final long BT_PAIR_WINDOW_MS = 60_000L;

    private final CarMode car = new CarMode();
    private volatile boolean running = false;
    private Thread poll;

    private boolean btRxRegistered = false;
    private volatile byte[] pendingPin = null;
    private volatile String pairingAddr = null;
    private volatile long pairingUntilMs = 0L;

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        startAsForeground();
        if (i != null && ACTION_BT_PAIR.equals(i.getAction())) {
            ensureBtListener();
            pairByAddress(i.getStringExtra("addr"), i.getStringExtra("pin"));
        }
        cleanupInstaller();
        if (!running) {
            running = true;
            poll = new Thread(this::pollLoop, "helper-poll");
            poll.start();
        }
        return START_STICKY;
    }

    // How long a just-launched installer gets before it is treated as a
    // stale leftover instead of still legitimately running.
    private static final long INSTALLER_GRACE_MS = 3 * 60_000L;

    /** Removes a leftover temporary installer from an old, abandoned run, but
     * leaves alone one that InstallResultReceiver launched moments ago. */
    private void cleanupInstaller() {
        try {
            getPackageManager().getPackageInfo("com.geely.installer", 0);
            long launchedMs = getSharedPreferences("modehelper", MODE_PRIVATE)
                    .getLong("installer_launched_ms", 0);
            long age = System.currentTimeMillis() - launchedMs;
            if (launchedMs > 0 && age < INSTALLER_GRACE_MS) {
                Log.i(TAG, "cleanupInstaller: launched " + (age / 1000)
                        + "s ago, still within grace -- leaving it alone");
                return;
            }
            Log.i(TAG, "cleanupInstaller: found leftover com.geely.installer, uninstalling");
            Installer.uninstall(getApplicationContext(), "com.geely.installer");
        } catch (PackageManager.NameNotFoundException ignored) {
            // not installed, clean
        } catch (Throwable t) {
            Log.w(TAG, "cleanupInstaller: " + t);
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                if (!car.isReady()) car.connect(getApplicationContext());
                maybeWatchPower();
                maybeDexopt();
            } catch (Throwable t) { Log.w(TAG, "poll: " + t); }
            // Liveness heartbeat for BootReceiver's watchdog (survives process death).
            getSharedPreferences("modehelper", MODE_PRIVATE).edit()
                    .putLong("beat_poll", System.currentTimeMillis()).apply();
            try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
        }
    }

    // The car's own power states, once the car connection is up. One attempt
    // per process: a refusal will not change on retry.
    private boolean powerWatchTried;

    private void maybeWatchPower() {
        if (powerWatchTried || !car.isReady()) return;
        powerWatchTried = true;
        try {
            boolean ok = PowerWatch.register(car.powerManager(), state -> {
                Log.i(TAG, "power state " + state);
            });
            Log.i(TAG, "power watch: " + (ok ? "registered" : "no power manager"));
        } catch (Throwable t) { Log.w(TAG, "power watch: " + t); }
    }

    // Once a minute, on its own thread: a compile takes tens of seconds and
    // must not stall the poll loop's heartbeat. See Dexopt.
    private long lastDexoptCheck;
    private volatile boolean dexoptRunning;

    private void maybeDexopt() {
        long now = SystemClock.elapsedRealtime();
        if (dexoptRunning || now - lastDexoptCheck < 60_000) return;
        lastDexoptCheck = now;
        dexoptRunning = true;
        new Thread(() -> {
            try { Dexopt.ensure(getApplicationContext()); }
            finally { dexoptRunning = false; }
        }, "dexopt").start();
    }

    // Command-line pairing, driven by adb:
    //   adb shell am broadcast -a com.geely.modehelper.BT_PAIR --es addr AA:BB:CC:DD:EE:FF \
    //     -n com.geely.modehelper/.BtPairReceiver
    // Goes straight to BluetoothDevice.createBond(). The pairing prompt is
    // answered only for this address and only inside BT_PAIR_WINDOW_MS.
    private void pairByAddress(String addr, String pin) {
        if (addr == null) { Log.w(TAG, "btpair: no address"); return; }
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) { Log.w(TAG, "btpair: no adapter"); return; }
            BluetoothDevice dev = ad.getRemoteDevice(addr);
            Log.i(TAG, "btpair: " + addr + " current bond state = " + bondName(dev.getBondState()));
            pairingAddr = addr;
            pairingUntilMs = SystemClock.elapsedRealtime() + BT_PAIR_WINDOW_MS;
            if (pin != null) pendingPin = pin.getBytes();
            boolean started = dev.createBond();
            Log.i(TAG, "btpair: createBond(" + addr + ") = " + started);
            // Race against the framework's own auto-guessed PIN ("0000").
            if (pin != null) {
                boolean setOk = dev.setPin(pin.getBytes());
                Log.i(TAG, "btpair: speculative setPin = " + setOk);
            }
        } catch (Throwable t) { Log.w(TAG, "btpair: " + t); }
    }

    private void ensureBtListener() {
        if (btRxRegistered) return;
        btRxRegistered = true;
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent it) {
                BluetoothDevice dev = it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String addr = dev != null ? dev.getAddress() : "?";
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(it.getAction())) {
                    int state = it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    int prev = it.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                    int reason = it.getIntExtra("android.bluetooth.device.extra.REASON", -1);
                    Log.i(TAG, "btpair: " + addr + " bond " + bondName(prev) + " -> " + bondName(state)
                            + (state == BluetoothDevice.BOND_NONE ? " reason=" + reason : ""));
                } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(it.getAction())) {
                    String want = pairingAddr;
                    if (dev == null || want == null || !want.equalsIgnoreCase(addr)
                            || SystemClock.elapsedRealtime() > pairingUntilMs) {
                        Log.w(TAG, "btpair: ignoring unexpected pairing request from " + addr);
                        return;
                    }
                    int variant = it.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    Log.i(TAG, "btpair: " + addr + " pairing request, variant=" + variant);
                    try {
                        if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                            byte[] p = pendingPin != null ? pendingPin : "1234".getBytes();
                            Log.i(TAG, "btpair: setPin = " + dev.setPin(p));
                        } else {
                            Log.i(TAG, "btpair: setPairingConfirmation = " + dev.setPairingConfirmation(true));
                        }
                    } catch (Throwable t) { Log.w(TAG, "btpair: pairing response: " + t); }
                }
            }
        }, f);
    }

    private static String bondName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE: return "NONE";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            case BluetoothDevice.BOND_BONDED: return "BONDED";
            default: return "?(" + state + ")";
        }
    }

    private void startAsForeground() {
        try {
            String chn = "modehelper";
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        chn, "Помощник", android.app.NotificationManager.IMPORTANCE_MIN));
            }
            android.app.Notification n = new android.app.Notification.Builder(this, chn)
                    .setContentTitle("Помощник")
                    .setContentText("Запоминает режимы, считает запас хода")
                    .setSmallIcon(android.R.drawable.ic_menu_preferences)
                    .build();
            startForeground(51, n);
        } catch (Throwable t) { Log.e(TAG, "startForeground: " + t, t); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        running = false;
        car.disconnect();
        super.onDestroy();
    }
}