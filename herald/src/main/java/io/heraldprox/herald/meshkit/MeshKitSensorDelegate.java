//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.heraldprox.herald.sensor.DefaultSensorDelegate;
import io.heraldprox.herald.sensor.datatype.PayloadData;
import io.heraldprox.herald.sensor.datatype.Proximity;
import io.heraldprox.herald.sensor.datatype.SensorType;
import io.heraldprox.herald.sensor.datatype.TargetIdentifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Herald SensorDelegate that parses incoming MeshKit payloads into MeshKitContact objects.
 * Maintains an in-memory contact log. Supports both v1 (plaintext) and v2 (encrypted) payloads.
 */
public class MeshKitSensorDelegate extends DefaultSensorDelegate {

    @NonNull
    private final MeshKitPayloadSupplier payloadSupplier;

    @Nullable
    private MeshKitContactListener contactListener;

    private final List<MeshKitContact> contacts = new ArrayList<>();
    private final Object lock = new Object();

    /**
     * Listener for contact detection events.
     */
    public interface MeshKitContactListener {
        void onContactDetected(@NonNull MeshKitContact contact);
    }

    public MeshKitSensorDelegate(@NonNull final MeshKitPayloadSupplier payloadSupplier) {
        this.payloadSupplier = payloadSupplier;
    }

    public void setContactListener(@Nullable final MeshKitContactListener listener) {
        this.contactListener = listener;
    }

    // MARK:- SensorDelegate

    @Override
    public void sensor(@NonNull final SensorType sensor, @NonNull final PayloadData didRead, @NonNull final TargetIdentifier fromTarget) {
        handlePayload(didRead, null);
    }

    @Override
    public void sensor(@NonNull final SensorType sensor, @NonNull final Proximity didMeasure, @NonNull final TargetIdentifier fromTarget, @NonNull final PayloadData withPayload) {
        handlePayload(withPayload, didMeasure.value);
    }

    @Override
    public void sensor(@NonNull final SensorType sensor, @NonNull final List<PayloadData> didShare, @NonNull final TargetIdentifier fromTarget) {
        for (final PayloadData payload : didShare) {
            handlePayload(payload, null);
        }
    }

    // MARK:- Contact log

    /**
     * Thread-safe snapshot of all detected contacts.
     */
    @NonNull
    public List<MeshKitContact> getContactLog() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(contacts));
        }
    }

    /**
     * Number of contacts detected.
     */
    public int getContactCount() {
        synchronized (lock) {
            return contacts.size();
        }
    }

    /**
     * Remove all contacts from the log.
     */
    public void clearContacts() {
        synchronized (lock) {
            contacts.clear();
        }
    }

    // MARK:- Internal

    private void handlePayload(@NonNull final PayloadData payload, @Nullable final Double rssi) {
        final UUID nodeId = payloadSupplier.decryptNodeId(payload);
        if (nodeId == null) return;
        // Skip self-detection
        if (nodeId.equals(payloadSupplier.getNodeId())) return;

        final Date timestamp = payloadSupplier.decryptTimestamp(payload);
        final MeshKitContact contact = new MeshKitContact(
                nodeId,
                rssi != null ? rssi : 0.0,
                timestamp != null ? timestamp : new Date()
        );
        synchronized (lock) {
            contacts.add(contact);
        }
        if (contactListener != null) {
            contactListener.onContactDetected(contact);
        }
    }
}
