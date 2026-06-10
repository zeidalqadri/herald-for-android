//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import io.heraldprox.herald.sensor.datatype.Data;
import io.heraldprox.herald.sensor.datatype.PayloadData;
import io.heraldprox.herald.sensor.datatype.PayloadTimestamp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class MeshKitPayloadSupplierTest {

    // MARK:- V1 tests (plaintext)

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
        assertEquals(MeshKitPayloadSupplier.VERSION_V1, payload.value[0]);
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

    // MARK:- V2 tests (encrypted)

    private byte[] testKey() {
        return new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                          0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F};
    }

    @Test
    public void testEncryptedPayloadProduces33Bytes() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier(UUID.randomUUID(), testKey());
        final PayloadData payload = supplier.payload(new PayloadTimestamp(), null);
        assertNotNull(payload);
        assertEquals(33, payload.value.length);
    }

    @Test
    public void testEncryptedPayloadVersionByte() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier(UUID.randomUUID(), testKey());
        final PayloadData payload = supplier.payload(new PayloadTimestamp(), null);
        assertEquals(MeshKitPayloadSupplier.VERSION_V2, payload.value[0]);
    }

    @Test
    public void testEncryptedRoundtripNodeId() {
        final UUID nodeId = UUID.randomUUID();
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier(nodeId, testKey());
        final PayloadData payload = supplier.payload(new PayloadTimestamp(), null);

        final UUID parsed = supplier.decryptNodeId(payload);
        assertNotNull(parsed);
        assertEquals(nodeId, parsed);
    }

    @Test
    public void testEncryptedRoundtripTimestamp() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier(UUID.randomUUID(), testKey());
        final Date knownDate = new Date(1718000000L * 1000);
        final PayloadData payload = supplier.payload(new PayloadTimestamp(knownDate), null);

        final Date parsed = supplier.decryptTimestamp(payload);
        assertNotNull(parsed);
        assertEquals(knownDate.getTime() / 1000, parsed.getTime() / 1000);
    }

    @Test
    public void testEncryptedNodeIdConsistentAcrossCalls() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier(UUID.randomUUID(), testKey());
        final PayloadData payload1 = supplier.payload(new PayloadTimestamp(), null);
        final PayloadData payload2 = supplier.payload(new PayloadTimestamp(), null);

        final UUID nodeId1 = supplier.decryptNodeId(payload1);
        final UUID nodeId2 = supplier.decryptNodeId(payload2);
        assertEquals(nodeId1, nodeId2);
    }

    @Test
    public void testEncryptedDifferentNonces() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier(UUID.randomUUID(), testKey());
        final PayloadData payload1 = supplier.payload(new PayloadTimestamp(), null);
        final PayloadData payload2 = supplier.payload(new PayloadTimestamp(), null);

        // Nonce bytes (1..12) should differ
        final byte[] nonce1 = Arrays.copyOfRange(payload1.value, 1, 13);
        final byte[] nonce2 = Arrays.copyOfRange(payload2.value, 1, 13);
        assertFalse(Arrays.equals(nonce1, nonce2));
    }

    @Test
    public void testEncryptedCrossSupplierRoundtrip() {
        final UUID nodeId = UUID.randomUUID();
        final byte[] key = testKey();
        final MeshKitPayloadSupplier supplierA = new MeshKitPayloadSupplier(nodeId, key);
        final MeshKitPayloadSupplier supplierB = new MeshKitPayloadSupplier(UUID.randomUUID(), key);

        final PayloadData payload = supplierA.payload(new PayloadTimestamp(), null);
        final UUID parsed = supplierB.decryptNodeId(payload);
        assertEquals(nodeId, parsed);
    }

    @Test
    public void testEncryptedPayloadSplitting() {
        final MeshKitPayloadSupplier supplier = new MeshKitPayloadSupplier(UUID.randomUUID(), testKey());
        final PayloadData p1 = supplier.payload(new PayloadTimestamp(), null);
        final PayloadData p2 = supplier.payload(new PayloadTimestamp(), null);

        final byte[] combined = new byte[p1.value.length + p2.value.length];
        System.arraycopy(p1.value, 0, combined, 0, p1.value.length);
        System.arraycopy(p2.value, 0, combined, p1.value.length, p2.value.length);

        final List<PayloadData> split = supplier.payload(new Data(combined));
        assertEquals(2, split.size());
        assertEquals(33, split.get(0).value.length);
        assertEquals(33, split.get(1).value.length);
    }

    @Test
    public void testDecryptNodeIdHandlesV1Payload() {
        final UUID nodeId = UUID.randomUUID();
        final MeshKitPayloadSupplier v1Supplier = new MeshKitPayloadSupplier(nodeId);
        final MeshKitPayloadSupplier v2Supplier = new MeshKitPayloadSupplier(UUID.randomUUID(), testKey());

        final PayloadData v1Payload = v1Supplier.payload(new PayloadTimestamp(), null);
        // v2 supplier can still parse v1 payloads
        final UUID parsed = v2Supplier.decryptNodeId(v1Payload);
        assertEquals(nodeId, parsed);
    }

    @Test
    public void testWrongKeyCannotDecrypt() {
        final UUID nodeId = UUID.randomUUID();
        final byte[] key1 = testKey();
        final byte[] key2 = new byte[]{0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
                                        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F};
        final MeshKitPayloadSupplier supplierA = new MeshKitPayloadSupplier(nodeId, key1);
        final MeshKitPayloadSupplier supplierB = new MeshKitPayloadSupplier(UUID.randomUUID(), key2);

        final PayloadData payload = supplierA.payload(new PayloadTimestamp(), null);
        final UUID parsed = supplierB.decryptNodeId(payload);
        // Decrypts to garbage UUID, not the original
        assertNotEquals(nodeId, parsed);
    }
}
