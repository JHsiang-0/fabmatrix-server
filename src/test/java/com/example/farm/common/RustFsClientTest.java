package com.example.farm.common;

import com.example.farm.common.exception.StorageException;
import com.example.farm.common.utils.RustFsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RustFsClientTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private S3Presigner s3Presigner;
    @Mock
    private PresignedGetObjectRequest presignedRequest;

    private RustFsClient client;

    @BeforeEach
    void setUp() {
        client = new RustFsClient();
        ReflectionTestUtils.setField(client, "s3Client", s3Client);
        ReflectionTestUtils.setField(client, "s3Presigner", s3Presigner);
        ReflectionTestUtils.setField(client, "endpoint", "http://rustfs.test:9000");
        ReflectionTestUtils.setField(client, "bucket", "farm");
    }

    @Test
    void uploadsMultipartFileAndReturnsPathStyleUrl() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "demo.gcode", "text/plain", "G1 X1\n".getBytes());
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String url = client.uploadFile("uploads/demo.gcode", file);

        assertThat(url).isEqualTo("http://rustfs.test:9000/farm/uploads/demo.gcode");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void convertsUploadFailureToStorageException() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "demo.gcode", "text/plain", "G1 X1\n".getBytes());
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("storage unavailable"));

        assertThatThrownBy(() -> client.uploadFile("uploads/demo.gcode", file))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void generatesPresignedUrlWithRequestedDuration() throws Exception {
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);
        when(presignedRequest.url()).thenReturn(new URL("http://rustfs.test:9000/farm/demo.gcode?signature=ok"));

        String url = client.getPresignedUrl("demo.gcode", Duration.ofMinutes(30));

        assertThat(url).contains("signature=ok");
        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void convertsPresignedUrlFailureToStorageException() {
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(new IllegalStateException("presign unavailable"));

        assertThatThrownBy(() -> client.getPresignedUrl("demo.gcode", Duration.ofMinutes(30)))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void convertsDeleteFailureToStorageException() {
        doThrow(new IllegalStateException("delete unavailable"))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        assertThatThrownBy(() -> client.deleteFile("demo.gcode"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void convertsStoredObjectUrlToPresignedObjectKey() {
        RustFsClient spyClient = spy(client);
        doReturn("https://example.test/signed-thumbnail")
                .when(spyClient).getPresignedUrl("thumbnails/20_demo.jpeg", Duration.ofMinutes(30));

        String url = spyClient.getPresignedUrlForObjectUrl(
                "http://rustfs.test:9000/farm/thumbnails/20_demo.jpeg", Duration.ofMinutes(30));

        assertThat(url).isEqualTo("https://example.test/signed-thumbnail");
        verify(spyClient).getPresignedUrl("thumbnails/20_demo.jpeg", Duration.ofMinutes(30));
    }

    @Test
    void deletesStoredObjectUrlUsingDecodedObjectKey() {
        RustFsClient spyClient = spy(client);
        doNothing().when(spyClient).deleteFile("thumbnails/20 demo.jpeg");

        spyClient.deleteFileByObjectUrl(
                "http://rustfs.test:9000/farm/thumbnails/20%20demo.jpeg");

        verify(spyClient).deleteFile("thumbnails/20 demo.jpeg");
    }
}
