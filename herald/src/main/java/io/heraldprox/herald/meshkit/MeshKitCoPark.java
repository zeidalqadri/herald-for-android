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
 * High-level facade wiring MeshKit for CoPark bay confirmations.
 *
 * Usage:
 *   MeshKitCoPark copark = new MeshKitCoPark("https://copark.example.com/api/meshkit/envelopes");
 *   copark.confirmBay("booking-123", "user-456");
 *   // Envelope queued. Flushes when internet available.
 */
public class MeshKitCoPark {

    public static final int APP_ID = 0x4350;  // ASCII "CP"

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

    public MeshKitCoPark(@NonNull final String endpointUrl) {
        this(endpointUrl, null);
    }

    /** Package-private constructor for testing with injectable HTTP client. */
    @VisibleForTesting
    MeshKitCoPark(@NonNull final String endpointUrl, @Nullable final MeshKitSurface.HttpClient httpClient) {
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
     * Queue a bay confirmation envelope.
     * Returns the envelope, or null if creation failed.
     */
    @Nullable
    public MeshKitEnvelope confirmBay(@NonNull final String bookingId, @NonNull final String userId) {
        final long timestamp = System.currentTimeMillis() / 1000;
        final String json = "{\"type\":\"bay_confirm\",\"booking_id\":\"" + bookingId
                + "\",\"user_id\":\"" + userId
                + "\",\"timestamp\":" + timestamp + "}";
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
