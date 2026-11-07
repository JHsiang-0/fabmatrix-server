package com.example.farm.protocol.file;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GCodeThumbnailCodecTest {
    @Test
    void decodesQoiRgbRgbaDiffLumaIndexAndRunAndEmitsPng() throws Exception {
        byte[] qoi = qoi(8, 1, new byte[]{(byte) 0xFF, (byte) 255, 0, 0, (byte) 255,
                (byte) 0xFE, (byte) 255, 0, 0, 0x79, (byte) 0xA1, (byte) 0x88, 50, (byte) 0xC1,
                (byte) 0xFF, 1, 2, 3, 4});
        var result = GCodeThumbnailCodec.normalize(qoi);
        var image = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertThat(result.descriptor().contentType()).isEqualTo("image/png");
        assertThat(image.getRGB(0, 0)).isEqualTo(0xFFFF0000);
        assertThat(image.getRGB(1, 0)).isEqualTo(0xFFFF0000);
        assertThat(image.getRGB(2, 0)).isEqualTo(0xFF0000FF);
        assertThat(image.getRGB(3, 0)).isEqualTo(0xFF010100);
        assertThat(image.getRGB(4, 0)).isEqualTo(0xFFFF0000);
        assertThat(image.getRGB(6, 0)).isEqualTo(0xFFFF0000);
        assertThat(image.getRGB(7, 0)).isEqualTo(0x04010203);
    }

    @Test
    void rejectsQoiOverrunTruncatedChunksAndPixelBombHeaders() throws Exception {
        assertThatThrownBy(() -> GCodeThumbnailCodec.normalize(qoi(1, 1, new byte[]{(byte) 0xFD})))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GCodeThumbnailCodec.normalize(qoi(1, 1, new byte[]{(byte) 0xFE, 1})))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GCodeThumbnailCodec.normalize(qoi(100_000, 100_000, new byte[]{(byte) 0xC0})))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GCodeThumbnailCodec.normalize(GCodeFileInspectorTest.png(1025, 1)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void convertsJpegAndRejectsUnknownOrOversizedBytes() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(GCodeFileInspectorTest.png(10, 10)));
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", jpeg);
        assertThat(GCodeThumbnailCodec.normalize(jpeg.toByteArray()).descriptor().contentType()).isEqualTo("image/png");
        assertThatThrownBy(() -> GCodeThumbnailCodec.normalize("not an image".getBytes())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GCodeThumbnailCodec.normalize(new byte[GCodeThumbnailCodec.MAX_INPUT_BYTES + 1]))
                .isInstanceOf(IOException.class);
    }

    static byte[] qoi(int width, int height, byte[] operations) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{'q', 'o', 'i', 'f'});
        bytes.write(ByteBuffer.allocate(8).putInt(width).putInt(height).array());
        bytes.write(new byte[]{4, 0});
        bytes.write(operations);
        bytes.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 1});
        return bytes.toByteArray();
    }
}
