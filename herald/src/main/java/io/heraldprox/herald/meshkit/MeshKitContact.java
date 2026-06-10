//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import androidx.annotation.NonNull;

import java.util.Date;
import java.util.UUID;

/**
 * Record of a detected MeshKit peer.
 */
public class MeshKitContact {
    @NonNull
    public final UUID remoteNodeId;
    public final double rssi;
    @NonNull
    public final Date timestamp;

    public MeshKitContact(@NonNull final UUID remoteNodeId, final double rssi, @NonNull final Date timestamp) {
        this.remoteNodeId = remoteNodeId;
        this.rssi = rssi;
        this.timestamp = timestamp;
    }

    public MeshKitContact(@NonNull final UUID remoteNodeId, final double rssi) {
        this(remoteNodeId, rssi, new Date());
    }
}
