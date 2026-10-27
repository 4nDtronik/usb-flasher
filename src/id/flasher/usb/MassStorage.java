package id.flasher.usb;

import android.hardware.usb.*;
import java.io.IOException;

/** Minimal USB Mass Storage (Bulk-Only Transport, SCSI) raw block access. */
public class MassStorage {
    private final UsbDeviceConnection conn;
    private final UsbInterface iface;
    private final UsbEndpoint in, out;
    private int tag = 1;
    public long blockCount;
    public int blockSize;

    private MassStorage(UsbDeviceConnection c, UsbInterface i, UsbEndpoint in, UsbEndpoint out) {
        this.conn = c; this.iface = i; this.in = in; this.out = out;
    }

    public static MassStorage open(UsbManager mgr, UsbDevice dev) throws IOException {
        for (int n = 0; n < dev.getInterfaceCount(); n++) {
            UsbInterface i = dev.getInterface(n);
            if (i.getInterfaceClass() != 8 || i.getInterfaceSubclass() != 6 || i.getInterfaceProtocol() != 0x50) continue;
            UsbEndpoint in = null, out = null;
            for (int e = 0; e < i.getEndpointCount(); e++) {
                UsbEndpoint ep = i.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep; else out = ep;
            }
            if (in == null || out == null) continue;
            UsbDeviceConnection c = mgr.openDevice(dev);
            if (c == null) throw new IOException("Tidak bisa membuka perangkat USB");
            if (!c.claimInterface(i, true)) { c.close(); throw new IOException("Gagal claim interface"); }
            MassStorage ms = new MassStorage(c, i, in, out);
            ms.init();
            return ms;
        }
        throw new IOException("Perangkat bukan USB mass storage");
    }

    private void init() throws IOException {
        // Tunggu unit siap
        for (int t = 0; t < 5; t++) {
            try { command(new byte[]{0x00,0,0,0,0,0}, null, 0, 0, false); break; }
            catch (IOException e) { if (t == 4) throw e; try { Thread.sleep(300); } catch (InterruptedException ie) {} }
        }
        byte[] cap = new byte[8];
        command(new byte[]{0x25,0,0,0,0,0,0,0,0,0}, cap, 0, 8, true);
        long last = ((cap[0] & 0xFFL) << 24) | ((cap[1] & 0xFF) << 16) | ((cap[2] & 0xFF) << 8) | (cap[3] & 0xFF);
        blockSize = ((cap[4] & 0xFF) << 24) | ((cap[5] & 0xFF) << 16) | ((cap[6] & 0xFF) << 8) | (cap[7] & 0xFF);
        blockCount = last + 1;
        if (blockSize <= 0) throw new IOException("Ukuran blok tidak valid");
    }

    public long sizeBytes() { return blockCount * blockSize; }

    private void command(byte[] cdb, byte[] data, int off, int len, boolean read) throws IOException {
        byte[] cbw = new byte[31];
        cbw[0]='U'; cbw[1]='S'; cbw[2]='B'; cbw[3]='C';
        int t = tag++;
        cbw[4]=(byte)t; cbw[5]=(byte)(t>>8); cbw[6]=(byte)(t>>16); cbw[7]=(byte)(t>>24);
        cbw[8]=(byte)len; cbw[9]=(byte)(len>>8); cbw[10]=(byte)(len>>16); cbw[11]=(byte)(len>>24);
        cbw[12]=(byte)(read ? 0x80 : 0x00);
        cbw[13]=0; cbw[14]=(byte)cdb.length;
        System.arraycopy(cdb, 0, cbw, 15, cdb.length);
        if (conn.bulkTransfer(out, cbw, 31, 5000) != 31) throw new IOException("Gagal kirim CBW");
        int done = 0;
        byte[] buf = (off == 0) ? data : null;
        while (len > 0 && done < len) {
            int chunk = Math.min(16384, len - done);
            int r;
            if (off == 0) {
                // salin ke buffer sementara agar offset aman di semua versi Android
                byte[] tmp = new byte[chunk];
                if (read) {
                    r = conn.bulkTransfer(in, tmp, chunk, 10000);
                    if (r > 0) System.arraycopy(tmp, 0, data, done, r);
                } else {
                    System.arraycopy(data, done, tmp, 0, chunk);
                    r = conn.bulkTransfer(out, tmp, chunk, 10000);
                }
            } else r = -1;
            if (r < 0) { conn.controlTransfer(0x02, 1, 0, read ? in.getAddress() : out.getAddress(), null, 0, 1000); throw new IOException("Transfer data gagal"); }
            done += r;
        }
        byte[] csw = new byte[13];
        int r = conn.bulkTransfer(in, csw, 13, 10000);
        if (r != 13) {
            conn.bulkTransfer(in, csw, 13, 10000);
            r = conn.bulkTransfer(in, csw, 13, 10000);
        }
        if (r != 13 || csw[0]!='U' || csw[1]!='S' || csw[2]!='B' || csw[3]!='S') throw new IOException("CSW tidak valid");
        if (csw[12] != 0) throw new IOException("Perintah SCSI gagal (status " + csw[12] + ")");
    }

    /** Tulis len byte (kelipatan blockSize) mulai dari LBA. */
    public void write(long lba, byte[] data, int len) throws IOException {
        int blocks = len / blockSize;
        byte[] cdb = {0x2A,0,(byte)(lba>>24),(byte)(lba>>16),(byte)(lba>>8),(byte)lba,0,(byte)(blocks>>8),(byte)blocks,0};
        command(cdb, data, 0, len, false);
    }

    public void read(long lba, byte[] data, int len) throws IOException {
        int blocks = len / blockSize;
        byte[] cdb = {0x28,0,(byte)(lba>>24),(byte)(lba>>16),(byte)(lba>>8),(byte)lba,0,(byte)(blocks>>8),(byte)blocks,0};
        command(cdb, data, 0, len, true);
    }

    public void close() {
        try { conn.releaseInterface(iface); } catch (Exception e) {}
        conn.close();
    }
}
