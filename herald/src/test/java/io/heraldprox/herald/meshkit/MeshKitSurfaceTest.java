//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MeshKitSurfaceTest {

    private final MeshKitIdentity identity = new MeshKitIdentity();
    private final UUID nodeId = UUID.randomUUID();
    private final String endpointUrl = "https://relay.test/meshkit/envelopes";

    private MeshKitEnvelope broadcast() throws MeshKitCryptoException {
        return MeshKitEnvelope.sealBroadcast("test".getBytes(), 1, nodeId, identity, (byte) 10);
    }

    // Successful delivery notifies callback and empties queue
    @Test
    public void testFlushDeliversAll() throws Exception {
        final MeshKitQueue queue = new MeshKitQueue();
        final List<byte[]> posts = new ArrayList<>();
        final MeshKitSurface surface = new MeshKitSurface(queue, endpointUrl,
                (url, body) -> { posts.add(body); return 200; });

        final List<MeshKitEnvelope> delivered = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(2);
        surface.setCallback(new MeshKitSurface.Callback() {
            public void onDelivered(MeshKitEnvelope e) { delivered.add(e); latch.countDown(); }
            public void onFailed(MeshKitEnvelope e, Exception err) { latch.countDown(); }
        });

        queue.enqueue(broadcast());
        queue.enqueue(broadcast());
        surface.flush();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, posts.size());
        assertEquals(2, delivered.size());
        assertEquals(0, queue.getCount());
    }

    // Empty queue flush is a no-op — no HTTP calls
    @Test
    public void testFlushEmptyQueueNoOp() throws Exception {
        final MeshKitQueue queue = new MeshKitQueue();
        final List<byte[]> posts = new ArrayList<>();
        final MeshKitSurface surface = new MeshKitSurface(queue, endpointUrl,
                (url, body) -> { posts.add(body); return 200; });

        surface.flush();
        Thread.sleep(100);

        assertEquals(0, posts.size());
    }

    // HTTP 503 re-enqueues envelope and fires onFailed
    @Test
    public void testHTTPErrorReenqueues() throws Exception {
        final MeshKitQueue queue = new MeshKitQueue();
        final MeshKitSurface surface = new MeshKitSurface(queue, endpointUrl,
                (url, body) -> 503);

        final List<MeshKitEnvelope> failed = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        surface.setCallback(new MeshKitSurface.Callback() {
            public void onDelivered(MeshKitEnvelope e) { latch.countDown(); }
            public void onFailed(MeshKitEnvelope e, Exception err) { failed.add(e); latch.countDown(); }
        });

        queue.enqueue(broadcast());
        surface.flush();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, failed.size());
        assertEquals(1, queue.getCount()); // re-enqueued
    }

    // Network exception re-enqueues envelope and fires onFailed
    @Test
    public void testNetworkExceptionReenqueues() throws Exception {
        final MeshKitQueue queue = new MeshKitQueue();
        final MeshKitSurface surface = new MeshKitSurface(queue, endpointUrl,
                (url, body) -> { throw new IOException("timeout"); });

        final List<Exception> errors = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        surface.setCallback(new MeshKitSurface.Callback() {
            public void onDelivered(MeshKitEnvelope e) { latch.countDown(); }
            public void onFailed(MeshKitEnvelope e, Exception err) { errors.add(err); latch.countDown(); }
        });

        queue.enqueue(broadcast());
        surface.flush();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains("timeout"));
        assertEquals(1, queue.getCount());
    }

    // Posts serialized bytes to the correct URL
    @Test
    public void testPostURLAndBody() throws Exception {
        final MeshKitQueue queue = new MeshKitQueue();
        final List<String> urls = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final MeshKitSurface surface = new MeshKitSurface(queue, endpointUrl,
                (url, body) -> { urls.add(url); bodies.add(body); return 200; });

        final MeshKitEnvelope env = broadcast();
        queue.enqueue(env);

        final CountDownLatch latch = new CountDownLatch(1);
        surface.setCallback(new MeshKitSurface.Callback() {
            public void onDelivered(MeshKitEnvelope e) { latch.countDown(); }
            public void onFailed(MeshKitEnvelope e, Exception err) { latch.countDown(); }
        });
        surface.flush();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(endpointUrl, urls.get(0));
        assertArrayEquals(env.serialize(), bodies.get(0));
    }

    // onConnectivityAvailable triggers flush
    @Test
    public void testOnConnectivityAvailableTriggersFlush() throws Exception {
        final MeshKitQueue queue = new MeshKitQueue();
        final List<byte[]> posts = new ArrayList<>();
        final MeshKitSurface surface = new MeshKitSurface(queue, endpointUrl,
                (url, body) -> { posts.add(body); return 200; });

        final CountDownLatch latch = new CountDownLatch(1);
        surface.setCallback(new MeshKitSurface.Callback() {
            public void onDelivered(MeshKitEnvelope e) { latch.countDown(); }
            public void onFailed(MeshKitEnvelope e, Exception err) { latch.countDown(); }
        });

        queue.enqueue(broadcast());
        surface.onConnectivityAvailable();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, posts.size());
    }
}
