package io.saas.forge.tenantaccess.application.brand;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
}
