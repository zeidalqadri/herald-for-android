//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * High-level facade wiring MeshKit for BuzzBuzz job relay.
 *
 * Usage:
 *   MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz("https://buzzbuzz.example.com/api/meshkit/envelopes");
 *   buzzbuzz.acceptJob("jr-123", 456);
 *   // Envelope queued. Flushes when internet available.
 */
public class MeshKitBuzzBuzz {

    public static final int APP_ID = 0x4242;  // ASCII "BB"

    @NonNull
    private final MeshKitIdentity identity;
    @NonNull
    private final UUID nodeId;
    @NonNull
    private final MeshKitQueue queue;
    @NonNull
    private final MeshKitSurface surface;
    @NonNull
    private final MeshKitPayloadSupplier payloadSupplier;
    @NonNull
    private final MeshKitSensorDelegate sensorDelegate;
    @Nullable
    private MeshKitSurfaceMonitor surfaceMonitor;

    public MeshKitBuzzBuzz(@NonNull final String endpointUrl) {
        this(endpointUrl, null);
    }

    /** Package-private constructor for testing with injectable HTTP client. */
    @VisibleForTesting
    MeshKitBuzzBuzz(@NonNull final String endpointUrl, @Nullable final MeshKitSurface.HttpClient httpClient) {
        this.identity = new MeshKitIdentity();
        this.nodeId = UUID.randomUUID();
        this.queue = new MeshKitQueue();
        this.payloadSupplier = new MeshKitPayloadSupplier(nodeId);
        this.sensorDelegate = new MeshKitSensorDelegate(payloadSupplier);
        if (httpClient != null) {
            this.surface = new MeshKitSurface(queue, endpointUrl, httpClient);
        } else {
            this.surface = new MeshKitSurface(queue, endpointUrl);
        }
    }

    public void start(@NonNull final Context context) {
        surfaceMonitor = new MeshKitSurfaceMonitor(queue);
        surfaceMonitor.setListener(envelopes -> surface.flush());
        surfaceMonitor.start(context);
    }

    public void stop(@NonNull final Context context) {
        if (surfaceMonitor != null) {
            surfaceMonitor.stop(context);
            surfaceMonitor = null;
        }
    }

    /**
     * Queue a job acceptance envelope.
     * Returns the envelope, or null if creation failed.
     */
    @Nullable
    public MeshKitEnvelope acceptJob(@NonNull final String jobRequestId, final int providerId) {
        final String json = "{\"type\":\"job_accept\",\"job_request_id\":\"" + jobRequestId
                + "\",\"provider_id\":" + providerId + "}";
        final byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        try {
            final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(jsonBytes, APP_ID, nodeId, identity, (byte) 10);
            queue.enqueue(envelope);
            surface.flush();
            return envelope;
        } catch (final MeshKitCryptoException e) {
            return null;
        }
    }

    /**
     * Queue a booking completion envelope.
     * Returns the envelope, or null if creation failed.
     */
    @Nullable
    public MeshKitEnvelope completeBooking(@NonNull final String bookingId, final int providerId) {
        final String json = "{\"type\":\"booking_complete\",\"booking_id\":\"" + bookingId
                + "\",\"provider_id\":" + providerId + "}";
        final byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        try {
            final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(jsonBytes, APP_ID, nodeId, identity, (byte) 10);
            queue.enqueue(envelope);
            surface.flush();
            return envelope;
        } catch (final MeshKitCryptoException e) {
            return null;
        }
    }

    @NonNull
    public MeshKitIdentity getIdentity() { return identity; }

    @NonNull
    public UUID getNodeId() { return nodeId; }

    @NonNull
    public MeshKitQueue getQueue() { return queue; }
}
