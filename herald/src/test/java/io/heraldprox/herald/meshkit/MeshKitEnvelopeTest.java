//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.UUID;

public class MeshKitEnvelopeTest {

    // MARK:- Broadcast tests

    @Test
    public void testBroadcastSealAndOpen() throws MeshKitCryptoException {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final UUID nodeId = UUID.randomUUID();
        final byte[] payload = "hello broadcast".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(payload, 42, nodeId, identity, 5);
        final byte[] opened = envelope.openBroadcast();

        assertArrayEquals(payload, opened);
    }

    @Test
    public void testBroadcastSignatureVerification() throws MeshKitCryptoException {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final UUID nodeId = UUID.randomUUID();
        final byte[] payload = "verify me".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(payload, 1, nodeId, identity, 3);
        assertTrue(envelope.verifySignature());
    }

    @Test
    public void testBroadcastTamperedPayload() throws MeshKitCryptoException {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final UUID nodeId = UUID.randomUUID();
        final byte[] payload = "original".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(payload, 1, nodeId, identity, 3);
        final byte[] serialized = envelope.serialize();

        // Tamper with a payload byte (find the payload region and flip a bit)
        // The payload is after version(1)+flags(1)+appId(2)+nodeId(16)+pubKey(32)+ts(4)+ttl(1)+hops(1)+payloadLen(2) = 60
        serialized[60] ^= 0xFF;

        final MeshKitEnvelope tampered = MeshKitEnvelope.deserialize(serialized);
        assertFalse(tampered.verifySignature());
    }

    @Test
    public void testBroadcastSerializeDeserialize() throws MeshKitCryptoException {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final UUID nodeId = UUID.randomUUID();
        final byte[] payload = "roundtrip".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(payload, 100, nodeId, identity, 7);
        final byte[] serialized = envelope.serialize();
        final MeshKitEnvelope deserialized = MeshKitEnvelope.deserialize(serialized);

        assertTrue(deserialized.verifySignature());
        final byte[] opened = deserialized.openBroadcast();
        assertArrayEquals(payload, opened);
        assertEquals(nodeId, deserialized.getFromNodeId());
        assertEquals(100, deserialized.getAppId());
    }

    // MARK:- Directed tests

    @Test
    public void testDirectedSealAndOpen() throws MeshKitCryptoException {
        final MeshKitIdentity sender = new MeshKitIdentity();
        final MeshKitIdentity recipient = new MeshKitIdentity();
        final UUID fromNode = UUID.randomUUID();
        final UUID toNode = UUID.randomUUID();
        final byte[] payload = "secret message".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealDirected(payload, 99, fromNode,
                sender, toNode, recipient.getAgreementPublicKey(), 10);
        final byte[] opened = envelope.openDirected(recipient);

        assertArrayEquals(payload, opened);
    }

    @Test
    public void testDirectedWrongRecipient() throws MeshKitCryptoException {
        final MeshKitIdentity sender = new MeshKitIdentity();
        final MeshKitIdentity recipient = new MeshKitIdentity();
        final MeshKitIdentity wrongRecipient = new MeshKitIdentity();
        final UUID fromNode = UUID.randomUUID();
        final UUID toNode = UUID.randomUUID();
        final byte[] payload = "secret message".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealDirected(payload, 99, fromNode,
                sender, toNode, recipient.getAgreementPublicKey(), 10);

        try {
            envelope.openDirected(wrongRecipient);
            fail("Expected MeshKitCryptoException for wrong recipient");
        } catch (final MeshKitCryptoException e) {
            // Expected: decryption fails with wrong key
        }
    }

    @Test
    public void testDirectedSignatureVerification() throws MeshKitCryptoException {
        final MeshKitIdentity sender = new MeshKitIdentity();
        final MeshKitIdentity recipient = new MeshKitIdentity();
        final UUID fromNode = UUID.randomUUID();
        final UUID toNode = UUID.randomUUID();
        final byte[] payload = "signed".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealDirected(payload, 1, fromNode,
                sender, toNode, recipient.getAgreementPublicKey(), 5);
        assertTrue(envelope.verifySignature());
    }

    @Test
    public void testDirectedSerializeDeserialize() throws MeshKitCryptoException {
        final MeshKitIdentity sender = new MeshKitIdentity();
        final MeshKitIdentity recipient = new MeshKitIdentity();
        final UUID fromNode = UUID.randomUUID();
        final UUID toNode = UUID.randomUUID();
        final byte[] payload = "directed roundtrip".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealDirected(payload, 55, fromNode,
                sender, toNode, recipient.getAgreementPublicKey(), 8);
        final byte[] serialized = envelope.serialize();
        final MeshKitEnvelope deserialized = MeshKitEnvelope.deserialize(serialized);

        assertTrue(deserialized.verifySignature());
        final byte[] opened = deserialized.openDirected(recipient);
        assertArrayEquals(payload, opened);
        assertEquals(fromNode, deserialized.getFromNodeId());
        assertEquals(toNode, deserialized.getToNodeId());
        assertEquals(55, deserialized.getAppId());
    }

    // MARK:- Relay and field tests

    @Test
    public void testRelay() throws MeshKitCryptoException {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final UUID nodeId = UUID.randomUUID();
        final byte[] payload = "relay test".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(payload, 1, nodeId, identity, 3);
        assertEquals(0, envelope.getHops());

        assertTrue(envelope.relay());  // hops=1, ttl=3 → true
        assertEquals(1, envelope.getHops());

        assertTrue(envelope.relay());  // hops=2, ttl=3 → true
        assertEquals(2, envelope.getHops());

        assertFalse(envelope.relay()); // hops=3, ttl=3 → false
        assertEquals(3, envelope.getHops());
    }

    @Test
    public void testMaxPayloadSize() {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final UUID nodeId = UUID.randomUUID();
        final byte[] oversized = new byte[MeshKitEnvelope.MAX_PAYLOAD_SIZE + 1];

        try {
            MeshKitEnvelope.sealBroadcast(oversized, 1, nodeId, identity, 5);
            fail("Expected MeshKitCryptoException for oversized payload");
        } catch (final MeshKitCryptoException e) {
            assertTrue(e.getMessage().contains("maximum size"));
        }
    }

    @Test
    public void testTimestampPreserved() throws MeshKitCryptoException {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final UUID nodeId = UUID.randomUUID();
        final byte[] payload = "ts test".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(payload, 1, nodeId, identity, 5);
        final int ts = envelope.getTimestamp();

        final byte[] serialized = envelope.serialize();
        final MeshKitEnvelope deserialized = MeshKitEnvelope.deserialize(serialized);

        assertEquals(ts, deserialized.getTimestamp());
    }

    @Test
    public void testAppIdPreserved() throws MeshKitCryptoException {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final UUID nodeId = UUID.randomUUID();
        final byte[] payload = "appid test".getBytes();

        final MeshKitEnvelope envelope = MeshKitEnvelope.sealBroadcast(payload, 12345, nodeId, identity, 5);
        assertEquals(12345, envelope.getAppId());

        final byte[] serialized = envelope.serialize();
        final MeshKitEnvelope deserialized = MeshKitEnvelope.deserialize(serialized);
        assertEquals(12345, deserialized.getAppId());
    }
}
