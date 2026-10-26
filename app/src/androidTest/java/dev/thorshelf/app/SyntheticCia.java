package dev.thorshelf.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Generated format fixture; contains only synthetic bytes, never a game or executable. */
final class SyntheticCia {
    static final long TITLE_ID = 0x0004000000FEDE00L;
    static final int TITLE_VERSION = 0x1234;
    static final int CONTENT_LENGTH = 0xE00;
    static final int EXEFS_OFFSET = 0xA00;
    static final byte[] PAYLOAD = payload();
    private static final byte[] ZERO_KEY = new byte[16];
    // Same public application common-key constant already vendored in ctrtool/KeyBag.cpp.
    // Used solely to encrypt the generated zero title key in this synthetic test ticket.
    private static final byte[] COMMON_KEY = hex("64c5fd55dd3ad988325baaec5243db98");

    private SyntheticCia() { }

    static byte[] create(boolean outerEncrypted, boolean mixed) throws Exception {
        return create(outerEncrypted, mixed, false, false, TITLE_ID);
    }

    /** A fake executable plus a data partition at sparse index 2, with distinct NCCH IDs. */
    static byte[] createCartridge(boolean encrypted) throws Exception {
        return create(encrypted, true, !encrypted, true, TITLE_ID);
    }

    static byte[] createUpdate() throws Exception {
        return create(false, false, true, false, 0x0004000E00FEDE00L);
    }

    private static byte[] create(boolean outerEncrypted, boolean mixed, boolean decrypted,
                                 boolean cartridge, long titleId) throws Exception {
        int count = mixed ? 2 : 1;
        byte[][] contents = new byte[count][];
        byte[][] contentHashes = new byte[count][];
        int[] indexes = mixed ? new int[]{0, cartridge ? 2 : 7} : new int[]{0};
        for (int i = 0; i < count; i++) {
            contents[i] = ncch(titleId, titleId + indexes[i],
                    !decrypted && (cartridge || !mixed || i != 0), !cartridge || i == 0);
            // NoEncryption takes precedence. Packaging must not normalize these
            // harmless flags and thereby alter an already verified payload.
            if (cartridge && decrypted) contents[i][0x18F] |= 1;
            contentHashes[i] = hash(contents[i]);
            if (outerEncrypted) {
                byte[] iv = new byte[16];
                be(iv).putShort(0, (short) indexes[i]);
                contents[i] = crypt("AES/CBC/NoPadding", contents[i], ZERO_KEY, iv);
            }
        }

        byte[] ticket = ticket(indexes, titleId);
        int body = 0x140;
        int groups = body + 0xC4;
        int records = groups + 0x900;
        byte[] tmd = new byte[records + count * 48];
        ByteBuffer t = be(tmd);
        t.putInt(0, 0x10004);
        put(tmd, body, "Root-CA00000003-CP0000000b".getBytes(StandardCharsets.US_ASCII));
        tmd[body + 0x40] = 1;
        t.putLong(body + 0x4C, titleId);
        t.putInt(body + 0x54, 0x40);
        t.putShort(body + 0x9C, (short) TITLE_VERSION);
        t.putShort(body + 0x9E, (short) count);
        for (int i = 0; i < count; i++) {
            int record = records + i * 48;
            t.putInt(record, 0x12340000 + i);
            t.putShort(record + 4, (short) indexes[i]);
            t.putShort(record + 6, (short) (outerEncrypted ? 1 : 0));
            t.putLong(record + 8, contents[i].length);
            put(tmd, record + 16, contentHashes[i]);
        }
        t.putShort(groups + 2, (short) count);
        put(tmd, groups + 4, hash(Arrays.copyOfRange(tmd, records, tmd.length)));
        put(tmd, body + 0xA4, hash(Arrays.copyOfRange(tmd, groups, records)));

        int ticketOffset = align(0x2020, 64);
        int tmdOffset = align(ticketOffset + ticket.length, 64);
        int contentOffset = align(tmdOffset + tmd.length, 64);
        byte[] cia = new byte[contentOffset + count * CONTENT_LENGTH];
        ByteBuffer h = le(cia);
        h.putInt(0, 0x2020);
        h.putInt(12, ticket.length);
        h.putInt(16, tmd.length);
        h.putLong(24, (long) count * CONTENT_LENGTH);
        for (int i = 0; i < count; i++) {
            cia[0x20 + indexes[i] / 8] |= (byte) (0x80 >>> (indexes[i] % 8));
            put(cia, contentOffset + i * CONTENT_LENGTH, contents[i]);
        }
        put(cia, ticketOffset, ticket);
        put(cia, tmdOffset, tmd);
        return cia;
    }

    private static byte[] ticket(int[] indexes, long titleId) throws Exception {
        byte[] ticket = new byte[0x350];
        ByteBuffer t = be(ticket);
        t.putInt(0, 0x10004);
        put(ticket, 0x140, "Root-CA00000003-XS0000000c".getBytes(StandardCharsets.US_ASCII));
        ticket[0x1BC] = 1;
        byte[] iv = new byte[16];
        be(iv).putLong(0, titleId);
        put(ticket, 0x1BF, crypt("AES/CBC/NoPadding", ZERO_KEY, COMMON_KEY, iv));
        t.putLong(0x1DC, titleId);
        t.putShort(0x1E6, (short) TITLE_VERSION);
        int ext = 0x2A4;
        t.putShort(ext, (short) 1);
        t.putShort(ext + 2, (short) 20);
        t.putInt(ext + 4, 0xAC);
        t.putInt(ext + 8, 20);
        t.putShort(ext + 12, (short) 1);
        t.putShort(ext + 14, (short) 20);
        t.putInt(ext + 20, 40);
        t.putInt(ext + 24, 1);
        t.putInt(ext + 28, 132);
        t.putInt(ext + 32, 132);
        t.putShort(ext + 36, (short) 3);
        for (int index : indexes) ticket[ext + 44 + index / 8] |= (byte) (0x80 >>> (index % 8));
        return ticket;
    }

    private static byte[] ncch(long titleId, long contentId, boolean encrypted,
                               boolean executable) throws Exception {
        byte[] ncch = new byte[CONTENT_LENGTH];
        ByteBuffer h = le(ncch);
        put(ncch, 0x100, new byte[]{'N', 'C', 'C', 'H'});
        h.putInt(0x104, CONTENT_LENGTH / 512);
        h.putLong(0x108, contentId);
        h.putShort(0x112, (short) 2);
        h.putLong(0x118, titleId);
        put(ncch, 0x150, "TEST-THORSHELF".getBytes(StandardCharsets.US_ASCII));
        h.putInt(0x180, executable ? 0x400 : 0);
        ncch[0x18C] = 1;
        ncch[0x18D] = (byte) (executable ? 2 : 1);
        ncch[0x18F] = (byte) (encrypted ? 3 : 6);
        h.putInt(0x1A0, EXEFS_OFFSET / 512);
        h.putInt(0x1A4, 2);
        h.putInt(0x1A8, 1);

        byte[] exheader = new byte[0x800];
        put(exheader, 0, "TSHTEST".getBytes(StandardCharsets.US_ASCII));
        le(exheader).putLong(0x200, titleId);
        if (executable) put(ncch, 0x160, hash(Arrays.copyOf(exheader, 0x400)));
        byte[] exefs = new byte[0x400];
        put(exefs, 0, (executable ? ".code" : "asset").getBytes(StandardCharsets.US_ASCII));
        le(exefs).putInt(12, PAYLOAD.length);
        put(exefs, 0x1E0, hash(PAYLOAD));
        put(exefs, 0x200, PAYLOAD);
        put(ncch, 0x1C0, hash(Arrays.copyOf(exefs, 0x200)));
        if (encrypted) {
            byte[] iv = new byte[16];
            be(iv).putLong(0, contentId);
            iv[8] = 1;
            exheader = crypt("AES/CTR/NoPadding", exheader, ZERO_KEY, iv);
            iv[8] = 2;
            exefs = crypt("AES/CTR/NoPadding", exefs, ZERO_KEY, iv);
        }
        put(ncch, 0x200, exheader);
        put(ncch, EXEFS_OFFSET, exefs);
        return ncch;
    }

    static int contentOffset(byte[] cia) {
        ByteBuffer h = le(cia);
        return align(align(align(align(h.getInt(0), 64) + h.getInt(8), 64)
                + h.getInt(12), 64) + h.getInt(16), 64);
    }

    static byte[] hash(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static byte[] crypt(String mode, byte[] bytes, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(mode);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(bytes);
    }

    private static byte[] payload() {
        byte[] label = "THORSHELF SYNTHETIC ENCRYPTION TEST - NOT A GAME. ".getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[512];
        for (int i = 0; i < result.length; i++) result[i] = label[i % label.length];
        return result;
    }

    private static ByteBuffer le(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer be(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN); }
    private static int align(int value, int block) { return (value + block - 1) & -block; }
    private static void put(byte[] target, int offset, byte[] value) { System.arraycopy(value, 0, target, offset, value.length); }
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
