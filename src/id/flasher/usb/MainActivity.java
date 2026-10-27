package id.flasher.usb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.*;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

public class MainActivity extends Activity {
    private static final String ACTION_PERM = "id.flasher.usb.PERM";
    private static final int REQ_PICK = 1, REQ_SAVE = 2;
    private static final int BG = 0xFF0F1720, CARD = 0xFF1A2332, ACCENT = 0xFF2DD4BF, INK = 0xFF04201C,
            TEXT = 0xFFE6EDF3, MUTED = 0xFF8B98A9, DANGER = 0xFFF87171;

    private UsbManager usb;
    private final List<UsbDevice> devices = new ArrayList<UsbDevice>();
    private Uri imageUri, saveUri;
    private long imageSize, readLimit;
    private String imageName = "image";
    private boolean readMode;
    private volatile boolean busy, cancel;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Spinner spinner;
    private TextView fileText, saveText, status, tabWrite, tabRead, note;
    private EditText limitEdit;
    private CheckBox verify;
    private Button action;
    private ProgressBar bar;
    private View cardFile, cardSave;
    private float density;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_PERM.equals(i.getAction())) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) startOp(d);
                else log("Izin USB ditolak.");
            } else refreshDevices();
        }
    };

    private int dp(int v) { return (int) (v * density + 0.5f); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void styleButton(Button b, int bgColor, int textColor) {
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(15);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(bgColor, 14), null));
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        c.setLayoutParams(lp);
        TextView t = text(title, 11, MUTED, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        return c;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        usb = (UsbManager) getSystemService(USB_SERVICE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(24), dp(18), dp(24));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));

        col.addView(text("USB Flasher", 28, TEXT, true));
        TextView sub = text("Tulis image ke flashdisk, atau cadangkan flashdisk ke file image.", 14, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(18));
        col.addView(sub);

        // Tab Tulis / Baca
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(rounded(CARD, 14));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabWrite = tab("Tulis ke USB");
        tabRead = tab("Baca dari USB");
        tabWrite.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setMode(false); } });
        tabRead.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setMode(true); } });
        tabs.addView(tabWrite, new LinearLayout.LayoutParams(0, -2, 1f));
        tabs.addView(tabRead, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(tabs);

        // Kartu perangkat
        LinearLayout cd = card("FLASHDISK USB (OTG)");
        spinner = new Spinner(this);
        cd.addView(spinner);
        Button refresh = new Button(this);
        refresh.setText("Segarkan daftar USB");
        styleButton(refresh, 0xFF263248, TEXT);
        refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { refreshDevices(); } });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(8);
        cd.addView(refresh, rl);
        col.addView(cd);

        // Kartu file (mode tulis)
        LinearLayout cf = card("FILE IMAGE (ISO / IMG)");
        fileText = text("Belum ada file dipilih", 16, TEXT, false);
        cf.addView(fileText);
        Button pick = new Button(this);
        pick.setText("Pilih file");
        styleButton(pick, 0xFF263248, TEXT);
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickFile(); } });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = dp(10);
        cf.addView(pick, pl);
        verify = new CheckBox(this);
        verify.setText("Verifikasi setelah menulis");
        verify.setTextColor(TEXT);
        verify.setChecked(true);
        verify.setButtonTintList(ColorStateList.valueOf(ACCENT));
        cf.addView(verify);
        cardFile = cf;
        col.addView(cf);

        // Kartu simpan (mode baca)
        LinearLayout cs = card("SIMPAN KE FILE");
        saveText = text("Belum ada lokasi dipilih", 16, TEXT, false);
        cs.addView(saveText);
        Button save = new Button(this);
        save.setText("Pilih lokasi & nama file");
        styleButton(save, 0xFF263248, TEXT);
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickSave(); } });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(10);
        cs.addView(save, sl);
        limitEdit = new EditText(this);
        limitEdit.setHint("Batasi ukuran dalam MB (kosong = seluruh disk)");
        limitEdit.setHintTextColor(MUTED);
        limitEdit.setTextColor(TEXT);
        limitEdit.setTextSize(14);
        limitEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        limitEdit.setBackgroundTintList(ColorStateList.valueOf(MUTED));
        cs.addView(limitEdit);
        cardSave = cs;
        col.addView(cs);

        // Tombol aksi
        action = new Button(this);
        styleButton(action, ACCENT, INK);
        action.setTextSize(16);
        action.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onAction(); } });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(18);
        col.addView(action, al);

        // Progres
        LinearLayout cp = card("PROGRES");
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2C3A52));
        bar.setScaleY(2.2f);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(10));
        bl.topMargin = dp(4); bl.bottomMargin = dp(12);
        cp.addView(bar, bl);
        status = text("Siap.", 14, TEXT, false);
        cp.addView(status);
        col.addView(cp);

        note = text("", 12, MUTED, false);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        col.addView(note);

        setContentView(sv);
        setMode(false);

        IntentFilter f = new IntentFilter(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(receiver, f);
        refreshDevices();
    }

    private TextView tab(String s) {
        TextView t = text(s, 14, MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(12), dp(8), dp(12));
        return t;
    }

    private void setMode(boolean read) {
        if (busy) return;
        readMode = read;
        tabWrite.setBackground(read ? null : rounded(ACCENT, 11));
        tabWrite.setTextColor(read ? MUTED : INK);
        tabRead.setBackground(read ? rounded(ACCENT, 11) : null);
        tabRead.setTextColor(read ? INK : MUTED);
        cardFile.setVisibility(read ? View.GONE : View.VISIBLE);
        cardSave.setVisibility(read ? View.VISIBLE : View.GONE);
        action.setText(read ? "Mulai baca" : "Mulai tulis");
        note.setText(read
            ? "Membaca seluruh disk menghasilkan file sebesar kapasitas flashdisk (mis. 32 GB). Pastikan penyimpanan cukup, atau batasi ukuran."
            : "Mode raw (seperti DD mode Etcher). Image hybrid seperti Linux, OpenWrt, atau Raspberry Pi bisa boot; ISO Windows biasa tidak.");
        status.setText("Siap.");
        bar.setProgress(0);
    }

    @Override protected void onDestroy() { super.onDestroy(); unregisterReceiver(receiver); }

    private void log(final String s) {
        ui.post(new Runnable() { public void run() { status.setText(s); } });
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    private void pickSave() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, "usb-backup.img");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (res != RESULT_OK || data == null) return;
        Uri u = data.getData();
        String name = "file"; long size = -1;
        try {
            Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int s = c.getColumnIndex(OpenableColumns.SIZE);
                    if (n >= 0) name = c.getString(n);
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
                }
                c.close();
            }
        } catch (Exception e) {}
        if (req == REQ_PICK) {
            imageUri = u; imageName = name; imageSize = size;
            fileText.setText(name + (size > 0 ? "  (" + fmt(size) + ")" : ""));
        } else if (req == REQ_SAVE) {
            saveUri = u;
            saveText.setText(name);
        }
    }

    private void refreshDevices() {
        devices.clear();
        List<String> names = new ArrayList<String>();
        for (UsbDevice d : usb.getDeviceList().values()) {
            boolean ms = false;
            for (int i = 0; i < d.getInterfaceCount(); i++)
                if (d.getInterface(i).getInterfaceClass() == 8) ms = true;
            if (ms) {
                devices.add(d);
                String nm = d.getProductName() != null ? d.getProductName() : "USB storage";
                names.add(nm + "  (" + String.format("%04x:%04x", d.getVendorId(), d.getProductId()) + ")");
            }
        }
        if (names.isEmpty()) names.add("Tidak ada flashdisk terdeteksi");
        spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names) {
            @Override public View getView(int p, View v, ViewGroup g) {
                TextView t = (TextView) super.getView(p, v, g);
                t.setTextColor(TEXT); t.setPadding(0, dp(8), 0, dp(8));
                return t;
            }
            @Override public View getDropDownView(int p, View v, ViewGroup g) {
                TextView t = (TextView) super.getDropDownView(p, v, g);
                t.setTextColor(TEXT); t.setBackgroundColor(CARD);
                return t;
            }
        });
    }

    private void onAction() {
        if (busy) { cancel = true; log("Membatalkan..."); return; }
        if (devices.isEmpty()) { log("Tidak ada flashdisk. Sambungkan lewat OTG lalu segarkan."); return; }
        final UsbDevice d = devices.get(spinner.getSelectedItemPosition());
        if (readMode) {
            if (saveUri == null) { log("Pilih lokasi simpan file dulu."); return; }
            String t = limitEdit.getText().toString().trim();
            try { readLimit = t.isEmpty() ? 0 : Long.parseLong(t) * 1048576L; }
            catch (NumberFormatException e) { log("Batas ukuran tidak valid."); return; }
            requestPermAndRun(d);
        } else {
            if (imageUri == null) { log("Pilih file image dulu."); return; }
            new AlertDialog.Builder(this)
                .setTitle("Hapus semua data?")
                .setMessage("Semua isi flashdisk \"" + spinner.getSelectedItem() + "\" akan DITIMPA oleh " + imageName + ". Tindakan ini tidak bisa dibatalkan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Tulis", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) { requestPermAndRun(d); }
                })
                .show();
        }
    }

    private void requestPermAndRun(UsbDevice d) {
        if (usb.hasPermission(d)) { startOp(d); return; }
        Intent in = new Intent(ACTION_PERM);
        in.setPackage(getPackageName());
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, in, 0));
    }

    private void setBusy(boolean b) {
        busy = b;
        if (b) { action.setText("Batalkan"); styleButton(action, DANGER, INK); }
        else { action.setText(readMode ? "Mulai baca" : "Mulai tulis"); styleButton(action, ACCENT, INK); }
        action.setTextSize(16);
    }

    private void startOp(final UsbDevice d) {
        if (busy) return;
        cancel = false;
        setBusy(true);
        final boolean rm = readMode;
        new Thread(new Runnable() {
            public void run() { if (rm) doRead(d); else doWrite(d); }
        }).start();
    }

    private void doWrite(UsbDevice d) {
        MassStorage ms = null;
        try {
            ms = MassStorage.open(usb, d);
            long dev = ms.sizeBytes();
            if (imageSize > 0 && imageSize > dev)
                throw new Exception("Image (" + fmt(imageSize) + ") lebih besar dari flashdisk (" + fmt(dev) + ")");
            int bs = ms.blockSize;
            int chunk = bs * Math.max(1, 131072 / bs);
            byte[] buf = new byte[chunk];
            long total = imageSize > 0 ? imageSize : 1, written = 0, lba = 0, t0 = System.currentTimeMillis();
            InputStream in = getContentResolver().openInputStream(imageUri);
            try {
                while (!cancel) {
                    int n = readFully(in, buf);
                    if (n <= 0) break;
                    int padded = ((n + bs - 1) / bs) * bs;
                    Arrays.fill(buf, n, padded, (byte) 0);
                    if ((lba + padded / bs) > ms.blockCount) throw new Exception("Melebihi kapasitas flashdisk");
                    ms.write(lba, buf, padded);
                    lba += padded / bs; written += n;
                    progress(written, total, "Menulis", t0);
                    if (n < chunk) break;
                }
            } finally { in.close(); }
            if (cancel) throw new Exception("Dibatalkan (isi flashdisk tidak lengkap)");

            if (verify.isChecked()) {
                byte[] rb = new byte[chunk];
                long done = 0, l2 = 0; t0 = System.currentTimeMillis();
                InputStream in2 = getContentResolver().openInputStream(imageUri);
                try {
                    while (!cancel) {
                        int n = readFully(in2, buf);
                        if (n <= 0) break;
                        int padded = ((n + bs - 1) / bs) * bs;
                        ms.read(l2, rb, padded);
                        for (int i = 0; i < n; i++)
                            if (buf[i] != rb[i]) throw new Exception("Verifikasi GAGAL di offset " + (done + i));
                        l2 += padded / bs; done += n;
                        progress(done, total, "Verifikasi", t0);
                        if (n < chunk) break;
                    }
                } finally { in2.close(); }
                if (cancel) throw new Exception("Verifikasi dibatalkan");
            }
            log("Selesai. " + fmt(written) + " berhasil ditulis" + (verify.isChecked() ? " dan terverifikasi." : "."));
        } catch (Exception e) {
            log("Gagal: " + e.getMessage());
        } finally {
            if (ms != null) ms.close();
            finishOp();
        }
    }

    private void doRead(UsbDevice d) {
        MassStorage ms = null;
        try {
            ms = MassStorage.open(usb, d);
            long total = ms.sizeBytes();
            if (readLimit > 0 && readLimit < total) total = readLimit;
            int bs = ms.blockSize;
            int chunk = bs * Math.max(1, 131072 / bs);
            byte[] buf = new byte[chunk];
            long done = 0, lba = 0, t0 = System.currentTimeMillis();
            OutputStream os = getContentResolver().openOutputStream(saveUri, "wt");
            if (os == null) throw new Exception("Tidak bisa membuka file tujuan");
            try {
                while (done < total && !cancel) {
                    int want = (int) Math.min((long) chunk, total - done);
                    int padded = ((want + bs - 1) / bs) * bs;
                    ms.read(lba, buf, padded);
                    os.write(buf, 0, want);
                    lba += padded / bs; done += want;
                    progress(done, total, "Membaca", t0);
                }
                os.flush();
            } finally { os.close(); }
            if (cancel) throw new Exception("Dibatalkan (file image tidak lengkap)");
            log("Selesai. " + fmt(done) + " tersimpan ke file image.");
        } catch (Exception e) {
            log("Gagal: " + e.getMessage());
        } finally {
            if (ms != null) ms.close();
            finishOp();
        }
    }

    private void finishOp() {
        ui.post(new Runnable() { public void run() { setBusy(false); } });
    }

    private int readFully(InputStream in, byte[] b) throws java.io.IOException {
        int t = 0;
        while (t < b.length) { int r = in.read(b, t, b.length - t); if (r < 0) break; t += r; }
        return t;
    }

    private void progress(final long done, final long total, final String what, long t0) {
        long el = Math.max(1, System.currentTimeMillis() - t0);
        final double mbs = (done / 1048576.0) / (el / 1000.0);
        final int pr = (int) Math.min(1000, done * 1000 / Math.max(1, total));
        final long eta = mbs > 0 ? (long) (((total - done) / 1048576.0) / mbs) : 0;
        ui.post(new Runnable() {
            public void run() {
                bar.setProgress(pr);
                status.setText(String.format("%s  %d%%\n%s / %s  •  %.1f MB/s  •  sisa %s", what, pr / 10, fmt(done), fmt(total), mbs, fmtTime(eta)));
            }
        });
    }

    private static String fmtTime(long s) {
        if (s < 60) return s + " dtk";
        if (s < 3600) return (s / 60) + " mnt " + (s % 60) + " dtk";
        return (s / 3600) + " j " + ((s % 3600) / 60) + " mnt";
    }

    private static String fmt(long b) {
        if (b < 1048576) return (b / 1024) + " KB";
        if (b < 1073741824L) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }
}
