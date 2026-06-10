// Copyright 2026 MeshKit Contributors
// SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MeshKitSurfaceMonitorTest {

    private final MeshKitIdentity identity = new MeshKitIdentity();
    private final UUID nodeId = UUID.randomUUID();

    private MeshKitEnvelope broadcast() throws MeshKitCryptoException {
        return MeshKitEnvelope.sealBroadcast("hello".getBytes(), 1, nodeId, identity, (byte) 10);
    }

    // 1. Basic construction
    @Test
    public void testMonitorCreation() {
        final MeshKitQueue queue = new MeshKitQueue();
        final MeshKitSurfaceMonitor monitor = new MeshKitSurfaceMonitor(queue);
        assertNotNull(monitor);
    }

    // 2. simulateSurface with empty queue — listener NOT called
    @Test
    public void testSimulateSurfaceEmptyQueue() {
        final MeshKitQueue queue = new MeshKitQueue();
        final MeshKitSurfaceMonitor monitor = new MeshKitSurfaceMonitor(queue);

        final List<List<MeshKitEnvelope>> calls = new ArrayList<>();
        monitor.setListener(calls::add);

        monitor.simulateSurface();

        assertEquals("Listener must not be called for an empty queue", 0, calls.size());
    }

    // 3. simulateSurface with envelopes — listener called with correct count
    @Test
    public void testSimulateSurfaceWithEnvelopes() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast());
        queue.enqueue(broadcast());

        final MeshKitSurfaceMonitor monitor = new MeshKitSurfaceMonitor(queue);
        final List<List<MeshKitEnvelope>> calls = new ArrayList<>();
        monitor.setListener(calls::add);

        monitor.simulateSurface();

        assertEquals(1, calls.size());
        assertEquals(2, calls.get(0).size());
    }

    // 4. simulateSurface drains the queue
    @Test
    public void testSimulateSurfaceDrainsQueue() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast());
        queue.enqueue(broadcast());
        queue.enqueue(broadcast());

        final MeshKitSurfaceMonitor monitor = new MeshKitSurfaceMonitor(queue);
        monitor.setListener(envelopes -> { /* consume */ });

        monitor.simulateSurface();

        assertEquals("Queue must be empty after surface flush", 0, queue.getCount());
    }

    // 5. simulateSurface with no listener — no NPE
    @Test
    public void testSimulateSurfaceNoListener() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast());

        final MeshKitSurfaceMonitor monitor = new MeshKitSurfaceMonitor(queue);
        // no listener set

        monitor.simulateSurface(); // must not throw
    }

    // 6. Multiple simulateSurface calls — each batch independently delivered
    @Test
    public void testMultipleSimulateSurface() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast());
        queue.enqueue(broadcast());

        final MeshKitSurfaceMonitor monitor = new MeshKitSurfaceMonitor(queue);
        final List<List<MeshKitEnvelope>> calls = new ArrayList<>();
        monitor.setListener(calls::add);

        // First surface flush — 2 envelopes
        monitor.simulateSurface();

        // Enqueue more, then flush again
        queue.enqueue(broadcast());
        queue.enqueue(broadcast());
        queue.enqueue(broadcast());
        monitor.simulateSurface();

        assertEquals(2, calls.size());
        assertEquals(2, calls.get(0).size());
        assertEquals(3, calls.get(1).size());
    }

    // 7. isConnected returns false before start() is called
    @Test
    public void testIsConnectedDefault() {
        final MeshKitQueue queue = new MeshKitQueue();
        final MeshKitSurfaceMonitor monitor = new MeshKitSurfaceMonitor(queue);
        assertFalse("isConnected must be false before start()", monitor.isConnected());
    }

    // 8. setListener — listener receives callback via simulateSurface
    @Test
    public void testSetListener() throws MeshKitCryptoException {
        final MeshKitQueue queue = new MeshKitQueue();
        queue.enqueue(broadcast());

        final MeshKitSurfaceMonitor monitor = new MeshKitSurfaceMonitor(queue);

        final List<MeshKitEnvelope> received = new ArrayList<>();
        monitor.setListener(envelopes -> received.addAll(envelopes));

        monitor.simulateSurface();

        assertEquals(1, received.size());
    }
}
