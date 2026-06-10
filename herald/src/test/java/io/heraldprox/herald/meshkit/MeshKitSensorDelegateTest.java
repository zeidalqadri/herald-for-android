//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import io.heraldprox.herald.sensor.datatype.PayloadData;
import io.heraldprox.herald.sensor.datatype.PayloadTimestamp;
import io.heraldprox.herald.sensor.datatype.Proximity;
import io.heraldprox.herald.sensor.datatype.ProximityMeasurementUnit;
import io.heraldprox.herald.sensor.datatype.SensorType;
import io.heraldprox.herald.sensor.datatype.TargetIdentifier;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class MeshKitSensorDelegateTest {

    private byte[] testKey() {
        return new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                          0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F};
    }

    // MARK:- V1 (plaintext) delegate tests

    @Test
    public void testDidReadCreatesContact() {
        final UUID remoteNodeId = UUID.randomUUID();
        final MeshKitPayloadSupplier remoteSupplier = new MeshKitPayloadSupplier(remoteNodeId);
        final MeshKitPayloadSupplier localSupplier = new MeshKitPayloadSupplier();
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(localSupplier);

        final PayloadData payload = remoteSupplier.payload(new PayloadTimestamp(), null);
        delegate.sensor(SensorType.BLE, payload, new TargetIdentifier("target1"));

        assertEquals(1, delegate.getContactCount());
        assertEquals(remoteNodeId, delegate.getContactLog().get(0).remoteNodeId);
    }

    @Test
    public void testDidMeasureWithPayloadCreatesContactWithRSSI() {
        final UUID remoteNodeId = UUID.randomUUID();
        final MeshKitPayloadSupplier remoteSupplier = new MeshKitPayloadSupplier(remoteNodeId);
        final MeshKitPayloadSupplier localSupplier = new MeshKitPayloadSupplier();
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(localSupplier);

        final PayloadData payload = remoteSupplier.payload(new PayloadTimestamp(), null);
        final Proximity proximity = new Proximity(ProximityMeasurementUnit.RSSI, -65.0);
        delegate.sensor(SensorType.BLE, proximity, new TargetIdentifier("target1"), payload);

        assertEquals(1, delegate.getContactCount());
        assertEquals(remoteNodeId, delegate.getContactLog().get(0).remoteNodeId);
        assertEquals(-65.0, delegate.getContactLog().get(0).rssi, 0.01);
    }

    @Test
    public void testInvalidPayloadIgnored() {
        final MeshKitPayloadSupplier localSupplier = new MeshKitPayloadSupplier();
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(localSupplier);

        final PayloadData badPayload = new PayloadData(new byte[]{(byte) 0xFF, 0x01, 0x02});
        delegate.sensor(SensorType.BLE, badPayload, new TargetIdentifier("target1"));

        assertEquals(0, delegate.getContactCount());
    }

    @Test
    public void testSelfDetectionIgnored() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier();
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(supplier);

        final PayloadData payload = supplier.payload(new PayloadTimestamp(), null);
        delegate.sensor(SensorType.BLE, payload, new TargetIdentifier("self"));

        assertEquals(0, delegate.getContactCount());
    }

    @Test
    public void testContactLogGrows() {
        final MeshKitPayloadSupplier localSupplier = new MeshKitPayloadSupplier();
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(localSupplier);

        for (int i = 0; i < 5; i++) {
            final MeshKitPayloadSupplier remote = new MeshKitPayloadSupplier();
            final PayloadData payload = remote.payload(new PayloadTimestamp(), null);
            delegate.sensor(SensorType.BLE, payload, new TargetIdentifier("target"));
        }

        assertEquals(5, delegate.getContactCount());
    }

    @Test
    public void testContactListenerCallbackFires() {
        final MeshKitPayloadSupplier remoteSupplier = new MeshKitPayloadSupplier();
        final MeshKitPayloadSupplier localSupplier = new MeshKitPayloadSupplier();
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(localSupplier);

        final List<MeshKitContact> received = new ArrayList<>();
        delegate.setContactListener(received::add);

        final PayloadData payload = remoteSupplier.payload(new PayloadTimestamp(), null);
        delegate.sensor(SensorType.BLE, payload, new TargetIdentifier("target1"));

        assertEquals(1, received.size());
        assertEquals(remoteSupplier.getNodeId(), received.get(0).remoteNodeId);
    }

    @Test
    public void testClearContacts() {
        final MeshKitPayloadSupplier localSupplier = new MeshKitPayloadSupplier();
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(localSupplier);

        final MeshKitPayloadSupplier remote = new MeshKitPayloadSupplier();
        final PayloadData payload = remote.payload(new PayloadTimestamp(), null);
        delegate.sensor(SensorType.BLE, payload, new TargetIdentifier("target"));

        assertEquals(1, delegate.getContactCount());
        delegate.clearContacts();
        assertEquals(0, delegate.getContactCount());
    }

    // MARK:- V2 (encrypted) delegate tests

    @Test
    public void testEncryptedDidReadCreatesContact() {
        final byte[] key = testKey();
        final UUID remoteNodeId = UUID.randomUUID();
        final MeshKitPayloadSupplier remoteSupplier = new MeshKitPayloadSupplier(remoteNodeId, key);
        final MeshKitPayloadSupplier localSupplier = new MeshKitPayloadSupplier(UUID.randomUUID(), key);
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(localSupplier);

        final PayloadData payload = remoteSupplier.payload(new PayloadTimestamp(), null);
        delegate.sensor(SensorType.BLE, payload, new TargetIdentifier("target1"));

        assertEquals(1, delegate.getContactCount());
        assertEquals(remoteNodeId, delegate.getContactLog().get(0).remoteNodeId);
    }

    @Test
    public void testDidShareCreatesMultipleContacts() {
        final MeshKitPayloadSupplier localSupplier = new MeshKitPayloadSupplier();
        final MeshKitSensorDelegate delegate = new MeshKitSensorDelegate(localSupplier);

        final MeshKitPayloadSupplier remote1 = new MeshKitPayloadSupplier();
        final MeshKitPayloadSupplier remote2 = new MeshKitPayloadSupplier();
        final PayloadData payload1 = remote1.payload(new PayloadTimestamp(), null);
        final PayloadData payload2 = remote2.payload(new PayloadTimestamp(), null);

        final List<PayloadData> shared = new ArrayList<>();
        shared.add(payload1);
        shared.add(payload2);
        delegate.sensor(SensorType.BLE, shared, new TargetIdentifier("relay"));

        assertEquals(2, delegate.getContactCount());
    }
}
