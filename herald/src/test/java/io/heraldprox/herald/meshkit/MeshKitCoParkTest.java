//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MeshKitCoParkTest {

    private static final String ENDPOINT = "https://copark.test/api/meshkit/envelopes";

    // 1. APP_ID constant is ASCII "CP"
    @Test
    public void testAppId() {
        assertEquals(0x4350, MeshKitCoPark.APP_ID);
    }

    // 2. confirmBay returns a non-null envelope
    @Test
    public void testConfirmBayCreatesEnvelope() {
        final MeshKitCoPark copark = new MeshKitCoPark(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = copark.confirmBay("booking-123", "user-456");
        assertNotNull(envelope);
    }

    // 3. confirmBay enqueues the envelope (or flushes it — queue starts at 0, after confirm it is 0 on
    //    immediate 200 flush, so we verify via delivered count using the surface callback)
    @Test
    public void testConfirmBayEnqueues() throws Exception {
        final List<byte[]> posts = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final MeshKitSurface.HttpClient client = (url, body) -> {
            posts.add(body);
            latch.countDown();
            return 200;
        };
        final MeshKitCoPark copark = new MeshKitCoPark(ENDPOINT, client);
        copark.confirmBay("booking-enqueue", "user-enqueue");
        assertTrue("Expected flush within 2s", latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, posts.size());
    }

    // 4. Payload contains booking_id
    @Test
    public void testConfirmBayPayloadContainsBookingId() throws Exception {
        final MeshKitCoPark copark = new MeshKitCoPark(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = copark.confirmBay("booking-abc", "user-xyz");
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload should contain booking_id", json.contains("\"booking_id\":\"booking-abc\""));
    }

    // 5. Payload contains user_id
    @Test
    public void testConfirmBayPayloadContainsUserId() throws Exception {
        final MeshKitCoPark copark = new MeshKitCoPark(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = copark.confirmBay("booking-abc", "user-xyz");
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload should contain user_id", json.contains("\"user_id\":\"user-xyz\""));
    }

    // 6. Payload type field is "bay_confirm"
    @Test
    public void testConfirmBayPayloadType() throws Exception {
        final MeshKitCoPark copark = new MeshKitCoPark(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = copark.confirmBay("booking-type", "user-type");
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload type should be bay_confirm", json.contains("\"type\":\"bay_confirm\""));
    }

    // 7. Envelope appId is 0x4350
    @Test
    public void testConfirmBayAppId() {
        final MeshKitCoPark copark = new MeshKitCoPark(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = copark.confirmBay("booking-appid", "user-appid");
        assertNotNull(envelope);
        assertEquals(MeshKitCoPark.APP_ID, envelope.getAppId());
    }

    // 8. After successful 200 flush, queue is empty
    @Test
    public void testConfirmBayFlushes() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final MeshKitSurface.HttpClient client = (url, body) -> {
            latch.countDown();
            return 200;
        };
        final MeshKitCoPark copark = new MeshKitCoPark(ENDPOINT, client);
        copark.confirmBay("booking-flush", "user-flush");
        assertTrue("Expected flush within 2s", latch.await(2, TimeUnit.SECONDS));
        // Give the surface executor time to clear the queue
        Thread.sleep(100);
        assertEquals(0, copark.getQueue().getCount());
    }

    // 9. Multiple confirmations all POST to mock
    @Test
    public void testMultipleConfirmations() throws Exception {
        final List<byte[]> posts = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(3);
        final MeshKitSurface.HttpClient client = (url, body) -> {
            synchronized (posts) { posts.add(body); }
            latch.countDown();
            return 200;
        };
        final MeshKitCoPark copark = new MeshKitCoPark(ENDPOINT, client);
        copark.confirmBay("booking-1", "user-1");
        copark.confirmBay("booking-2", "user-2");
        copark.confirmBay("booking-3", "user-3");
        assertTrue("Expected 3 POSTs within 3s", latch.await(3, TimeUnit.SECONDS));
        assertEquals(3, posts.size());
    }
}
