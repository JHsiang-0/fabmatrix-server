package com.example.farm.protocol.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** GET-only transport with an absolute deadline and a streaming response-byte ceiling. */
final class BoundedDeviceFileHttp {
    private final HttpClient client;

    BoundedDeviceFileHttp(HttpClient client) { this.client = client; }

    byte[] get(URI uri, Map<String, String> headers, int maximumBytes, long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("Device file read timed out");
        CompletableFuture<HttpResponse<byte[]>> response;
        try {
            var request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofNanos(remaining));
            headers.forEach(request::header);
            response = client.sendAsync(request.build(), ignored -> new LimitedSubscriber(maximumBytes));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid device file request headers");
        }
        try {
            HttpResponse<byte[]> result = response.get(remaining, TimeUnit.NANOSECONDS);
            if (result.statusCode() != 200) throw new StatusException(result.statusCode());
            return result.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Device file read interrupted");
        } catch (ExecutionException | TimeoutException failed) {
            // HTTP exception chains may contain a credential-bearing connection URI.
            throw new IOException("Device file read failed or exceeded its limits");
        } finally {
            response.cancel(true);
        }
    }

    static final class StatusException extends IOException {
        private final int status;
        StatusException(int status) { super("Device file HTTP status " + status); this.status = status; }
        int status() { return status; }
    }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maximum;
        private Flow.Subscription subscription;

        LimitedSubscriber(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            result.whenComplete((ignored, error) -> { if (error != null) value.cancel(); });
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > maximum - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Device file response is too large"));
                    return;
                }
                byte[] part = new byte[item.remaining()];
                item.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
