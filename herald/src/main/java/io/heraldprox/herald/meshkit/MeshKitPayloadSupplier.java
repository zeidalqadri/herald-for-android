//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.heraldprox.herald.sensor.Device;
import io.heraldprox.herald.sensor.PayloadDataSupplier;
import io.heraldprox.herald.sensor.datatype.Data;
import io.heraldprox.herald.sensor.datatype.LegacyPayloadData;
import io.heraldprox.herald.sensor.datatype.PayloadData;
import io.heraldprox.herald.sensor.datatype.PayloadTimestamp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * MeshKit payload data supplier. Bridges Herald BLE transport with MeshKit identity.
 * Payload format: [version:1][node_id:16][timestamp:4] = 21 bytes.
 * No encryption in this phase.
 */
public class MeshKitPayloadSupplier implements PayloadDataSupplier {
    public static final int PAYLOAD_LENGTH = 21;
    public static final byte VERSION = 1;

    @NonNull
    private final UUID nodeId;

    public MeshKitPayloadSupplier(@NonNull final UUID nodeId) {
        this.nodeId = nodeId;
    }

    public MeshKitPayloadSupplier() {
        this(UUID.randomUUID());
    }

    @NonNull
    public UUID getNodeId() {
        return nodeId;
    }

    // MARK:- PayloadDataSupplier

    @Nullable
    @Override
    public LegacyPayloadData legacyPayload(@NonNull final PayloadTimestamp timestamp, @Nullable final Device device) {
        return null;
    }

    @NonNull
    @Override
    public PayloadData payload(@NonNull final PayloadTimestamp timestamp, @Nullable final Device device) {
        final byte[] bytes = new byte[PAYLOAD_LENGTH];
        // [version:1]
        bytes[0] = VERSION;
        // [node_id:16] — RFC 4122 byte order (big-endian per half)
        final long msb = nodeId.getMostSignificantBits();
        final long lsb = nodeId.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[1 + i] = (byte) ((msb >>> (56 - 8 * i)) & 0xFF);
            bytes[9 + i] = (byte) ((lsb >>> (56 - 8 * i)) & 0xFF);
        }
        // [timestamp:4] — little-endian UInt32, seconds since Unix epoch
        final long epoch = timestamp.value.getTime() / 1000;
        bytes[17] = (byte) (epoch & 0xFF);
        bytes[18] = (byte) ((epoch >>> 8) & 0xFF);
        bytes[19] = (byte) ((epoch >>> 16) & 0xFF);
        bytes[20] = (byte) ((epoch >>> 24) & 0xFF);
        return new PayloadData(bytes);
    }

    @NonNull
    @Override
    public List<PayloadData> payload(@NonNull final Data data) {
        final List<PayloadData> payloads = new ArrayList<>();
        final byte[] bytes = data.value;
        for (int index = 0; (index + PAYLOAD_LENGTH) <= bytes.length; index += PAYLOAD_LENGTH) {
            final byte[] payloadBytes = new byte[PAYLOAD_LENGTH];
            System.arraycopy(bytes, index, payloadBytes, 0, PAYLOAD_LENGTH);
            payloads.add(new PayloadData(payloadBytes));
        }
        return payloads;
    }

    // MARK:- Parsing

    /**
     * Extract node ID from a MeshKit payload.
     */
    @Nullable
    public static UUID parseNodeId(@NonNull final PayloadData payload) {
        if (payload.value.length != PAYLOAD_LENGTH) return null;
        if (payload.value[0] != VERSION) return null;
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (payload.value[1 + i] & 0xFFL);
            lsb = (lsb << 8) | (payload.value[9 + i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }

    /**
     * Extract timestamp from a MeshKit payload.
     */
    @Nullable
    public static Date parseTimestamp(@NonNull final PayloadData payload) {
        if (payload.value.length != PAYLOAD_LENGTH) return null;
        final long epoch =
                (payload.value[17] & 0xFFL) |
                ((payload.value[18] & 0xFFL) << 8) |
                ((payload.value[19] & 0xFFL) << 16) |
                ((payload.value[20] & 0xFFL) << 24);
        return new Date(epoch * 1000);
    }
}
