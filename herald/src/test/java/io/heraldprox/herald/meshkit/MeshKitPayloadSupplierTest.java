//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import io.heraldprox.herald.sensor.datatype.Data;
import io.heraldprox.herald.sensor.datatype.PayloadData;
import io.heraldprox.herald.sensor.datatype.PayloadTimestamp;

import org.junit.Test;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public class MeshKitPayloadSupplierTest {

    @Test
    public void testPayloadProduces21Bytes() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier();
        final PayloadData payload = supplier.payload(new PayloadTimestamp(), null);
        assertNotNull(payload);
        assertEquals(21, payload.value.length);
    }

    @Test
    public void testPayloadVersionByte() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier();
        final PayloadData payload = supplier.payload(new PayloadTimestamp(), null);
        assertEquals(MeshKitPayloadSupplier.VERSION, payload.value[0]);
    }

    @Test
    public void testRoundtripNodeId() {
        final UUID nodeId = UUID.randomUUID();
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier(nodeId);
        final PayloadData payload = supplier.payload(new PayloadTimestamp(), null);

        final UUID parsed = MeshKitPayloadSupplier.parseNodeId(payload);
        assertNotNull(parsed);
        assertEquals(nodeId, parsed);
    }

    @Test
    public void testRoundtripTimestamp() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier();
        final Date knownDate = new Date(1718000000L * 1000);
        final PayloadData payload = supplier.payload(new PayloadTimestamp(knownDate), null);

        final Date parsed = MeshKitPayloadSupplier.parseTimestamp(payload);
        assertNotNull(parsed);
        assertEquals(knownDate.getTime() / 1000, parsed.getTime() / 1000);
    }

    @Test
    public void testNodeIdConsistentAcrossCalls() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier();
        final PayloadData payload1 = supplier.payload(new PayloadTimestamp(), null);
        final PayloadData payload2 = supplier.payload(new PayloadTimestamp(), null);

        final UUID nodeId1 = MeshKitPayloadSupplier.parseNodeId(payload1);
        final UUID nodeId2 = MeshKitPayloadSupplier.parseNodeId(payload2);
        assertEquals(nodeId1, nodeId2);
    }

    @Test
    public void testCrossSupplierRoundtrip() {
        final UUID nodeIdA = UUID.randomUUID();
        final MeshKitPayloadSupplier supplierA = new MeshKitPayloadSupplier(nodeIdA);
        final PayloadData payload = supplierA.payload(new PayloadTimestamp(), null);

        final UUID extracted = MeshKitPayloadSupplier.parseNodeId(payload);
        assertEquals(nodeIdA, extracted);
    }

    @Test
    public void testPayloadSplitting() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier();
        final PayloadData p1 = supplier.payload(new PayloadTimestamp(), null);
        final PayloadData p2 = supplier.payload(new PayloadTimestamp(), null);

        final byte[] combined = new byte[p1.value.length + p2.value.length];
        System.arraycopy(p1.value, 0, combined, 0, p1.value.length);
        System.arraycopy(p2.value, 0, combined, p1.value.length, p2.value.length);

        final List<PayloadData> split = supplier.payload(new Data(combined));
        assertEquals(2, split.size());
        assertEquals(21, split.get(0).value.length);
        assertEquals(21, split.get(1).value.length);
    }
}
