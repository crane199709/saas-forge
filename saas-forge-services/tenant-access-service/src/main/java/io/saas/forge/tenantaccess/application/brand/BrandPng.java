package io.saas.forge.tenantaccess.application.brand;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** 在写入对象存储前校验完整 PNG 数据流和实际解码尺寸。 */
public final class BrandPng {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private BrandPng() {}

    public enum Kind { LOGO, FAVICON }
    public record Dimensions(int width, int height) {}
    public static class Invalid extends RuntimeException {}
    public static class TooLarge extends RuntimeException {}

    public static Dimensions validate(byte[] bytes, Kind kind) {
        int maximum = kind == Kind.LOGO ? 2 * 1024 * 1024 : 256 * 1024;
        if (bytes != null && bytes.length > maximum) throw new TooLarge();
        if (bytes == null || bytes.length < 45
                || !Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, SIGNATURE.length))) throw new Invalid();
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        input.position(SIGNATURE.length);
        boolean ihdr = false;
        boolean idat = false;
        boolean end = false;
        int width = 0;
        int height = 0;
        while (input.remaining() >= 12 && !end) {
            int length = input.getInt();
            if (length < 0 || length > input.remaining() - 8) throw new Invalid();
            int typeStart = input.position();
            byte[] typeBytes = new byte[4];
            input.get(typeBytes);
            String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);
            if (!type.matches("[A-Za-z]{4}") || type.equals("acTL") || type.equals("fcTL")
                    || type.equals("fdAT")) throw new Invalid();
            if (!ihdr) {
                if (!type.equals("IHDR") || length != 13) throw new Invalid();
                width = input.getInt();
                height = input.getInt();
                input.position(typeStart + 4);
                ihdr = true;
            } else if (type.equals("IHDR")) throw new Invalid();
            if (type.equals("IDAT")) idat = true;
            if (type.equals("IEND")) {
                if (length != 0 || !idat) throw new Invalid();
                end = true;
            }
            var crc = new CRC32();
            crc.update(bytes, typeStart, length + 4);
            input.position(typeStart + 4 + length);
            if ((int) crc.getValue() != input.getInt()) throw new Invalid();
        }
        if (!ihdr || !idat || !end || input.hasRemaining() || width < 1 || height < 1
                || width > 2048 || height > 2048
                || (kind == Kind.FAVICON && (width != height || width < 32 || width > 256))) throw new Invalid();
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) throw new Invalid();
        } catch (IOException exception) {
            throw new Invalid();
        }
        return new Dimensions(width, height);
    }
}
