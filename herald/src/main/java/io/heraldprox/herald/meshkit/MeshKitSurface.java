//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drains the MeshKit envelope queue to a remote relay endpoint when internet connectivity
 * is available.
 *
 * <p>Register a {@link ConnectivityCallback} and call {@link #onConnectivityAvailable()} from
 * your app's {@code ConnectivityManager.NetworkCallback#onAvailable()} to trigger automatic
 * surface flush on internet return. Alternatively, call {@link #flush()} directly.</p>
 *
 * <pre>
 * MeshKitSurface surface = new MeshKitSurface(queue, "https://relay.example.com/meshkit/envelopes");
 * surface.setCallback(new MeshKitSurface.Callback() {
 *     public void onDelivered(MeshKitEnvelope e)  { ... }
 *     public void onFailed(MeshKitEnvelope e, Exception err) { ... }
 * });
 * // In ConnectivityManager callback:
 * surface.onConnectivityAvailable();
 * </pre>
 */
public class MeshKitSurface {

    /** Delivery result callbacks. */
    public interface Callback {
        void onDelivered(@NonNull MeshKitEnvelope envelope);
        void onFailed(@NonNull MeshKitEnvelope envelope, @NonNull Exception error);
    }

    /** Injectable HTTP client for testing. */
    public interface HttpClient {
        /** POST {@code body} to {@code url}. Returns HTTP status code. */
        int post(@NonNull String url, @NonNull byte[] body) throws Exception;
    }

    // Default: real HttpURLConnection
    private static final HttpClient DEFAULT_HTTP_CLIENT = (url, body) -> {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        try (final OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    };

    @NonNull
    private final MeshKitQueue queue;
    @NonNull
    private final String endpointUrl;
    @NonNull
    private final HttpClient httpClient;
    @Nullable
    private Callback callback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public MeshKitSurface(@NonNull final MeshKitQueue queue, @NonNull final String endpointUrl) {
        this(queue, endpointUrl, DEFAULT_HTTP_CLIENT);
    }

    @VisibleForTesting
    MeshKitSurface(@NonNull final MeshKitQueue queue, @NonNull final String endpointUrl,
                   @NonNull final HttpClient httpClient) {
        this.queue = queue;
        this.endpointUrl = endpointUrl;
        this.httpClient = httpClient;
    }

    public void setCallback(@Nullable final Callback callback) {
        this.callback = callback;
    }

    /** Call when internet connectivity becomes available. Triggers a flush. */
    public void onConnectivityAvailable() {
        flush();
    }

    /**
     * Drain all queued envelopes and deliver them to the remote endpoint.
     * Non-blocking — delivery runs on a background executor.
     */
    public void flush() {
        final List<MeshKitEnvelope> envelopes = queue.drain();
        if (envelopes.isEmpty()) return;
        executor.submit(() -> {
            for (final MeshKitEnvelope env : envelopes) {
                deliver(env);
            }
        });
    }

    // MARK:- Private

    private void deliver(@NonNull final MeshKitEnvelope envelope) {
        try {
            final int status = httpClient.post(endpointUrl, envelope.serialize());
            if (status == 200) {
                if (callback != null) callback.onDelivered(envelope);
            } else {
                final IOException err = new IOException("HTTP " + status);
                if (callback != null) callback.onFailed(envelope, err);
                queue.enqueue(envelope); // re-enqueue for next surface
            }
        } catch (final Exception e) {
            if (callback != null) callback.onFailed(envelope, e instanceof Exception ? (Exception) e : new Exception(e));
            queue.enqueue(envelope);
        }
    }
}
