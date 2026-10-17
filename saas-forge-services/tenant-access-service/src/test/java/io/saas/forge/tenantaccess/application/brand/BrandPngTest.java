package io.saas.forge.tenantaccess.application.brand;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class BrandPngTest {
    @Test
    void acceptsDecodedStaticFaviconAndRejectsAnimationChunk() throws Exception {
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] png = out.toByteArray();
        assertEquals(32, BrandPng.validate(png, BrandPng.Kind.FAVICON).width());

        byte[] animated = new byte[png.length + 12];
        System.arraycopy(png, 0, animated, 0, png.length - 12);
        animated[png.length - 8] = 'a';
        animated[png.length - 7] = 'c';
        animated[png.length - 6] = 'T';
        animated[png.length - 5] = 'L';
        System.arraycopy(png, png.length - 12, animated, png.length, 12);
        assertThrows(BrandPng.Invalid.class, () -> BrandPng.validate(animated, BrandPng.Kind.FAVICON));
    }

    @Test
    void rejectsFakePngAndWrongFaviconDimensions() throws Exception {
        assertThrows(BrandPng.Invalid.class, () -> BrandPng.validate("fake".getBytes(), BrandPng.Kind.LOGO));
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 64, BufferedImage.TYPE_INT_ARGB), "png", out);
        assertThrows(BrandPng.Invalid.class, () -> BrandPng.validate(out.toByteArray(), BrandPng.Kind.FAVICON));
        assertThrows(BrandPng.TooLarge.class,
                () -> BrandPng.validate(new byte[256 * 1024 + 1], BrandPng.Kind.FAVICON));
    }

    @Test
    void rejectsTruncatedTamperedAndTrailingPngDataBeforeAssetStorage() throws Exception {
        byte[] original = png(32, 32);
        byte[] badSignature = original.clone();
        badSignature[0] = 0;
        byte[] badCrc = original.clone();
        badCrc[badCrc.length - 13] ^= 1;
        byte[] trailing = Arrays.copyOf(original, original.length + 1);
        byte[] missingEnd = Arrays.copyOf(original, original.length - 12);

        for (byte[] bytes : new byte[][] {null, new byte[0], badSignature, badCrc, trailing, missingEnd}) {
            assertThrows(BrandPng.Invalid.class, () -> BrandPng.validate(bytes, BrandPng.Kind.LOGO));
        }
    }

    @Test
    void enforcesFaviconAndLogoPixelBoundsUsingPngHeaderAndDecodedContent() throws Exception {
        assertEquals(new BrandPng.Dimensions(2048, 1),
                BrandPng.validate(png(2048, 1), BrandPng.Kind.LOGO));
        assertThrows(BrandPng.Invalid.class,
                () -> BrandPng.validate(png(2049, 1), BrandPng.Kind.LOGO));
        assertThrows(BrandPng.Invalid.class,
                () -> BrandPng.validate(png(31, 31), BrandPng.Kind.FAVICON));
        assertEquals(new BrandPng.Dimensions(256, 256),
                BrandPng.validate(png(256, 256), BrandPng.Kind.FAVICON));
        assertThrows(BrandPng.Invalid.class,
                () -> BrandPng.validate(png(257, 257), BrandPng.Kind.FAVICON));
        assertThrows(BrandPng.TooLarge.class,
                () -> BrandPng.validate(new byte[2 * 1024 * 1024 + 1], BrandPng.Kind.LOGO));

        byte[] invalidDimensions = png(32, 32);
        ByteBuffer.wrap(invalidDimensions).putInt(16, 0);
        var crc = new CRC32();
        crc.update(invalidDimensions, 12, 17);
        ByteBuffer.wrap(invalidDimensions).putInt(29, (int) crc.getValue());
        assertThrows(BrandPng.Invalid.class,
                () -> BrandPng.validate(invalidDimensions, BrandPng.Kind.LOGO));
    }

    @Test
    void rejectsSpoofedPngChunkStructureEvenWhenMagicBytesAreValid() throws Exception {
        byte[] original = png(32, 32);
        byte[] negativeChunkLength = original.clone();
        ByteBuffer.wrap(negativeChunkLength).putInt(8, -1);
        byte[] excessiveChunkLength = original.clone();
        ByteBuffer.wrap(excessiveChunkLength).putInt(8, Integer.MAX_VALUE);
        byte[] wrongHeaderType = original.clone();
        wrongHeaderType[12] = 'J';
        writeHeaderCrc(wrongHeaderType);
        byte[] repeatedHeader = new byte[original.length + 25];
        System.arraycopy(original, 0, repeatedHeader, 0, 33);
        System.arraycopy(original, 8, repeatedHeader, 33, 25);
        System.arraycopy(original, 33, repeatedHeader, 58, original.length - 33);
        byte[] noPixelData = new byte[45];
        System.arraycopy(original, 0, noPixelData, 0, 33);
        System.arraycopy(original, original.length - 12, noPixelData, 33, 12);

        for (byte[] bytes : new byte[][] {
                negativeChunkLength, excessiveChunkLength, wrongHeaderType, repeatedHeader, noPixelData}) {
            assertThrows(BrandPng.Invalid.class, () -> BrandPng.validate(bytes, BrandPng.Kind.LOGO));
        }
    }

    private static void writeHeaderCrc(byte[] png) {
        var crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
    }

    private static byte[] png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }
}
