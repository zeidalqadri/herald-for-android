//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

public class MeshKitQueueTest {

    private MeshKitIdentity identity = new MeshKitIdentity();
    private UUID nodeId = UUID.randomUUID();

    private MeshKitEnvelope broadcast(int appId, byte ttl) throws MeshKitCryptoException {
        return MeshKitEnvelope.sealBroadcast("hello".getBytes(), appId, nodeId, identity, ttl);
    }

    private MeshKitEnvelope broadcast() throws MeshKitCryptoException {
        return broadcast(1, (byte) 10);
    }

    private MeshKitEnvelope directed(UUID recipientId, MeshKitIdentity recipientIdentity) throws MeshKitCryptoException {
        return MeshKitEnvelope.sealDirected("dm".getBytes(), 1, nodeId, identity, recipientId, recipientIdentity.getAgreementPublicKey(), 10);
    }

    // Basic operations

    @Test
    public void testEnqueueAndCount() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast());
        assertEquals(1, queue.getCount());
        assertFalse(queue.isEmpty());
    }

    @Test
    public void testCapacityDropsOldest() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue(3, MeshKitQueue.DEFAULT_TTL_MS);
        final MeshKitEnvelope e1 = broadcast(1, (byte) 10);
        final MeshKitEnvelope e2 = broadcast(2, (byte) 10);
        final MeshKitEnvelope e3 = broadcast(3, (byte) 10);
        final MeshKitEnvelope e4 = broadcast(4, (byte) 10);
        queue.enqueue(e1); queue.enqueue(e2); queue.enqueue(e3); queue.enqueue(e4);
        assertEquals(3, queue.getCount());
        // e1 (appId=1) should have been dropped
        final List<MeshKitEnvelope> all = queue.drain();
        for (final MeshKitEnvelope e : all) {
            assertNotEquals(1, e.getAppId());
        }
    }

    @Test
    public void testTTLEviction() throws MeshKitCryptoException, InterruptedException {
        final MeshKitQueue queue = new MeshKitQueue(200, 500L); // 500ms TTL
        queue.enqueue(broadcast());
        assertEquals(1, queue.getCount());
        Thread.sleep(1000);
        assertEquals(0, queue.getCount());
        assertTrue(queue.isEmpty());
    }

    // dequeueForPeer

    @Test
    public void testDequeueForPeerBroadcast() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast());
        final List<MeshKitEnvelope> got = queue.dequeueForPeer(UUID.randomUUID());
        assertEquals(1, got.size());
        assertEquals(0, queue.getCount()); // removed
    }

    @Test
    public void testDequeueForPeerBroadcastAtMaxHops() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast(1, (byte) 0)); // ttl=0, not relayable
        assertEquals(0, queue.dequeueForPeer(UUID.randomUUID()).size());
    }

    @Test
    public void testDequeueForPeerDirectedExactMatch() throws MeshKitCryptoException {
        final UUID recipientId = UUID.randomUUID();
        final MeshKitIdentity recipientIdentity = new MeshKitIdentity();
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(directed(recipientId, recipientIdentity));
        assertEquals(1, queue.dequeueForPeer(recipientId).size());
    }

    @Test
    public void testDequeueForPeerDirectedWrongPeer() throws MeshKitCryptoException {
        final UUID recipientId = UUID.randomUUID();
        final MeshKitIdentity recipientIdentity = new MeshKitIdentity();
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(directed(recipientId, recipientIdentity));
        assertEquals(0, queue.dequeueForPeer(UUID.randomUUID()).size()); // wrong peer
        assertEquals(1, queue.getCount()); // still there
    }

    // peekForPeer

    @Test
    public void testPeekDoesNotRemove() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast());
        final List<MeshKitEnvelope> peeked = queue.peekForPeer(UUID.randomUUID());
        assertEquals(1, peeked.size());
        assertEquals(1, queue.getCount()); // still there
    }

    // dequeueBroadcasts

    @Test
    public void testDequeueBroadcastsLeavesDirected() throws MeshKitCryptoException {
        final UUID recipientId = UUID.randomUUID();
        final MeshKitIdentity recipientIdentity = new MeshKitIdentity();
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast(1, (byte) 10));
        queue.enqueue(broadcast(2, (byte) 10));
        queue.enqueue(directed(recipientId, recipientIdentity));
        final List<MeshKitEnvelope> broadcasts = queue.dequeueBroadcasts();
        assertEquals(2, broadcasts.size());
        assertEquals(1, queue.getCount()); // directed remains
    }

    // remove / drain / clear

    @Test
    public void testRemovePredicate() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast(1, (byte) 10));
        queue.enqueue(broadcast(2, (byte) 10));
        final int removed = queue.remove(e -> e.getAppId() == 1);
        assertEquals(1, removed);
        assertEquals(1, queue.getCount());
    }

    @Test
    public void testDrain() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast(1, (byte) 10));
        queue.enqueue(broadcast(2, (byte) 10));
        final List<MeshKitEnvelope> all = queue.drain();
        assertEquals(2, all.size());
        assertEquals(0, queue.getCount());
    }

    @Test
    public void testClear() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast()); queue.enqueue(broadcast());
        queue.clear();
        assertEquals(0, queue.getCount());
    }
}
