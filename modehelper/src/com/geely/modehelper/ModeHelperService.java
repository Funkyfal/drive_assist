package com.geely.modehelper;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

// System helper (uid system), no UI. Polls every 4 s to:
// 1) hold the saved drive/regen mode while parked (P), overriding car defaults;
// 2) keep Wi-Fi on while parked and nudge scans when adrift.
// The wanted modes arrive via the SET_MODE broadcast, guarded by PERMISSION.
public class ModeHelperService extends Service {
    static final String TAG = "ModeHelper";
    /** Signature permission guarding every entry point reachable from outside. */
    static final String PERMISSION = "com.geely.modehelper.permission.CONTROL";
    static final String SET_MODE = "com.geely.modehelper.SET_MODE";
    public static final String ACTION_BT_PAIR = "com.geely.modehelper.svc.BT_PAIR";
    /** A pairing request is only answered for this long after BT_PAIR. */
    static final long BT_PAIR_WINDOW_MS = 60_000L;

    private final CarMode car = new CarMode();
    private volatile boolean running = false;
    private Thread poll;
    private BroadcastReceiver rx;
    private int lastGear = -999;

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
        if (rx == null) {
            rx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent it) {
                    if (!SET_MODE.equals(it.getAction())) return;
                    SharedPreferences.Editor e =
                            getSharedPreferences("modehelper", MODE_PRIVATE).edit();
                    StringBuilder log = new StringBuilder("default from sender:");
                    if (it.hasExtra("drive")) {
                        int d = it.getIntExtra("drive", CarMode.DRIVE_ECO);
                        if (d == CarMode.DRIVE_ECO || d == CarMode.DRIVE_COMFORT
                                || d == CarMode.DRIVE_SPORT) {
                            e.putInt("drive", d);
                            log.append(" drive=").append(d);
                        } else {
                            log.append(" drive=REJECTED(").append(d).append(")");
                        }
                    }
                    if (it.hasExtra("regen")) {
                        int r = it.getIntExtra("regen", CarMode.REGEN_MID);
                        if (r == CarMode.REGEN_LOW || r == CarMode.REGEN_MID
                                || r == CarMode.REGEN_HIGH) {
                            e.putInt("regen", r);
                            log.append(" regen=").append(r);
                        } else {
                            log.append(" regen=REJECTED(").append(r).append(")");
                        }
                    }
                    e.apply();
                    Log.i(TAG, log.toString());
                }
            };
            registerReceiver(rx, new IntentFilter(SET_MODE), PERMISSION, null);
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
                Integer g = car.readGear();
                boolean parked = (g != null && g == CarMode.GEAR_PARK);
                maybeWatchPower();
                if (g != null) {
                    if (g != lastGear) Log.i(TAG, "gear " + lastGear + " -> " + g);
                    lastGear = g;
                    if (parked) enforceModeParked();
                }
                // Wi-Fi is forced only while parked; driving lets it drop naturally.
                if (parked) ensureWifiOn();
                nudgeWifiScan();
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
            boolean ok = PowerWatch.register(car.powerManager(),
                    state -> Log.i(TAG, "power state " + state));
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

    // PARKED: read each mode and, if a choice was saved and the car differs,
    // correct it. No saved choice means "don't touch it": defaults the owner
    // never picked are never forced onto the car.
    private void enforceModeParked() {
        SharedPreferences p = getSharedPreferences("modehelper", MODE_PRIVATE);
        if (p.contains("drive")) {
            int want = p.getInt("drive", CarMode.DRIVE_ECO);
            Integer have = car.readDrive();
            if (have != null && have != want) {
                car.writeDrive(want);
                Log.i(TAG, "mode: drive " + have + " -> " + want);
            }
        }
        if (p.contains("regen")) {
            int want = p.getInt("regen", CarMode.REGEN_MID);
            Integer have = car.readRegen();
            if (have != null && have != want) {
                car.writeRegen(want);
                Log.i(TAG, "mode: regen " + have + " -> " + want);
            }
        }
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

    // Aggressive Wi-Fi while parked: switches it back on if it drops.
    private void ensureWifiOn() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                boolean ok = wm.setWifiEnabled(true);
                Log.i(TAG, "parked -> turning Wi-Fi on = " + ok);
            }
        } catch (Throwable t) { Log.w(TAG, "wifi: " + t); }
    }

    // Android 9's disconnected-scan backoff can delay reconnecting to saved
    // networks for minutes; a scan every 30 s avoids that. System-uid apps are
    // exempt from the background startScan() throttle.
    static final long SCAN_EVERY_MS = 30_000;
    private long lastScanMs = 0;

    private void nudgeWifiScan() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return;
            WifiInfo info = wm.getConnectionInfo();
            boolean associated = info != null && info.getNetworkId() != -1
                    && info.getSupplicantState() == SupplicantState.COMPLETED;
            if (associated) { lastScanMs = 0; return; }
            long now = SystemClock.elapsedRealtime();
            if (lastScanMs != 0 && now - lastScanMs < SCAN_EVERY_MS) return;
            lastScanMs = now;
            Log.i(TAG, "wifi adrift -> startScan = " + wm.startScan());
        } catch (Throwable t) { Log.w(TAG, "wifi scan: " + t); }
    }

    private void startAsForeground() {
        try {
            String chn = "modehelper";
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        chn, "Режим вождения", android.app.NotificationManager.IMPORTANCE_MIN));
            }
            android.app.Notification n = new android.app.Notification.Builder(this, chn)
                    .setContentTitle("Режим вождения")
                    .setContentText("Сохраняет режим и держит Wi-Fi")
                    .setSmallIcon(android.R.drawable.ic_menu_preferences)
                    .build();
            startForeground(51, n);
        } catch (Throwable t) { Log.e(TAG, "startForeground: " + t, t); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        running = false;
        if (rx != null) { try { unregisterReceiver(rx); } catch (Throwable ignored) {} rx = null; }
        car.disconnect();
        super.onDestroy();
    }
}