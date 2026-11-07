package com.example.farm.protocol.file;

import com.example.farm.protocol.model.GCodeFileMetadata;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;

/** Decodes bounded PNG/JPEG/QOI inputs and emits verified, portable PNG assets. */
public final class GCodeThumbnailCodec {
    public static final int MAX_IMAGE_BYTES = 512 * 1024;
    public static final int MAX_INPUT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_DIMENSION = 1024;
    private static final byte[] QOI_END = {0, 0, 0, 0, 0, 0, 0, 1};

    private GCodeThumbnailCodec() { }

    public static GCodeFileInspection.ThumbnailImage normalize(byte[] input) throws IOException {
        if (input.length < 8 || input.length > MAX_INPUT_BYTES) throw new IOException("Invalid thumbnail size");
        BufferedImage image = input[0] == 'q' && input[1] == 'o' && input[2] == 'i' && input[3] == 'f'
                ? decodeQoi(input) : decodeImage(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("Unable to encode thumbnail");
        byte[] png = output.toByteArray();
        if (png.length > MAX_IMAGE_BYTES) throw new IOException("Thumbnail exceeds the asset limit");
        var descriptor = new GCodeFileMetadata.Thumbnail(sha256(png), "image/png", image.getWidth(),
                image.getHeight(), png.length);
        return new GCodeFileInspection.ThumbnailImage(descriptor, png);
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the runtime", unavailable);
        }
    }

    private static BufferedImage decodeImage(byte[] bytes) throws IOException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported thumbnail format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("PNG") && !format.equalsIgnoreCase("JPEG")) {
                    throw new IOException("Unsupported thumbnail format");
                }
                dimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Invalid thumbnail image");
                return image;
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid thumbnail image");
        }
    }

    private static BufferedImage decodeQoi(byte[] bytes) throws IOException {
        if (bytes.length < 22 || !Arrays.equals(Arrays.copyOfRange(bytes, bytes.length - 8, bytes.length), QOI_END)) {
            throw new IOException("Incomplete QOI image");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int width = header.getInt(4);
        int height = header.getInt(8);
        dimensions(width, height);
        if ((bytes[12] != 3 && bytes[12] != 4) || (bytes[13] != 0 && bytes[13] != 1)) {
            throw new IOException("Invalid QOI header");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] index = new int[64];
        int r = 0, g = 0, b = 0, a = 255;
        int cursor = 14;
        int run = 0;
        int pixels = width * height;
        for (int pixel = 0; pixel < pixels; pixel++) {
            if (run > 0) {
                run--;
            } else {
                if (cursor >= bytes.length - 8) throw new IOException("Incomplete QOI pixel data");
                int tag = bytes[cursor++] & 255;
                if (tag == 0xFE) {
                    requireQoiBytes(cursor, 3, bytes.length);
                    r = bytes[cursor++] & 255;
                    g = bytes[cursor++] & 255;
                    b = bytes[cursor++] & 255;
                } else if (tag == 0xFF) {
                    requireQoiBytes(cursor, 4, bytes.length);
                    r = bytes[cursor++] & 255;
                    g = bytes[cursor++] & 255;
                    b = bytes[cursor++] & 255;
                    a = bytes[cursor++] & 255;
                } else {
                    switch (tag & 0xC0) {
                        case 0x00 -> {
                            int rgba = index[tag & 63];
                            r = (rgba >>> 16) & 255;
                            g = (rgba >>> 8) & 255;
                            b = rgba & 255;
                            a = rgba >>> 24;
                        }
                        case 0x40 -> {
                            r = (r + ((tag >>> 4) & 3) - 2) & 255;
                            g = (g + ((tag >>> 2) & 3) - 2) & 255;
                            b = (b + (tag & 3) - 2) & 255;
                        }
                        case 0x80 -> {
                            requireQoiBytes(cursor, 1, bytes.length);
                            int second = bytes[cursor++] & 255;
                            int dg = (tag & 63) - 32;
                            r = (r + dg + ((second >>> 4) & 15) - 8) & 255;
                            g = (g + dg) & 255;
                            b = (b + dg + (second & 15) - 8) & 255;
                        }
                        case 0xC0 -> {
                            run = tag & 63;
                            if (run >= pixels - pixel) throw new IOException("Invalid QOI run");
                        }
                        default -> throw new IOException("Invalid QOI opcode");
                    }
                }
                index[(r * 3 + g * 5 + b * 7 + a * 11) & 63] = a << 24 | r << 16 | g << 8 | b;
            }
            image.setRGB(pixel % width, pixel / width, a << 24 | r << 16 | g << 8 | b);
        }
        if (cursor != bytes.length - 8 || run != 0) throw new IOException("Unexpected QOI trailing data");
        return image;
    }

    private static void dimensions(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("Thumbnail dimensions exceed the asset limit");
        }
    }

    private static void requireQoiBytes(int position, int count, int length) throws IOException {
        if (position + count > length - 8) throw new IOException("Incomplete QOI pixel data");
    }
}
