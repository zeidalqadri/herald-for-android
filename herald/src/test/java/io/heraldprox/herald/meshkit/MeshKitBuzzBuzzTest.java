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

public class MeshKitBuzzBuzzTest {

    private static final String ENDPOINT = "https://buzzbuzz.test/api/meshkit/envelopes";

    // 1. APP_ID constant is ASCII "BB"
    @Test
    public void testAppId() {
        assertEquals(0x4242, MeshKitBuzzBuzz.APP_ID);
    }

    // 2. acceptJob returns a non-null envelope
    @Test
    public void testAcceptJobCreatesEnvelope() {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.acceptJob("jr-001", 123);
        assertNotNull(envelope);
    }

    // 3. acceptJob payload type is "job_accept"
    @Test
    public void testAcceptJobPayloadType() throws Exception {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.acceptJob("jr-type", 123);
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload type should be job_accept", json.contains("\"type\":\"job_accept\""));
    }

    // 4. acceptJob payload contains job_request_id
    @Test
    public void testAcceptJobPayloadContainsJobRequestId() throws Exception {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.acceptJob("jr-abc", 123);
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload should contain job_request_id", json.contains("\"job_request_id\":\"jr-abc\""));
    }

    // 5. acceptJob payload contains provider_id
    @Test
    public void testAcceptJobPayloadContainsProviderId() throws Exception {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.acceptJob("jr-abc", 456);
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload should contain provider_id", json.contains("\"provider_id\":456"));
    }

    // 6. completeBooking returns a non-null envelope
    @Test
    public void testCompleteBookingCreatesEnvelope() {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.completeBooking("bk-001", 123);
        assertNotNull(envelope);
    }

    // 7. completeBooking payload type is "booking_complete"
    @Test
    public void testCompleteBookingPayloadType() throws Exception {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.completeBooking("bk-type", 123);
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload type should be booking_complete", json.contains("\"type\":\"booking_complete\""));
    }

    // 8. completeBooking payload contains booking_id
    @Test
    public void testCompleteBookingPayloadContainsBookingId() throws Exception {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.completeBooking("bk-xyz", 123);
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload should contain booking_id", json.contains("\"booking_id\":\"bk-xyz\""));
    }

    // 9. completeBooking payload contains provider_id
    @Test
    public void testCompleteBookingPayloadContainsProviderId() throws Exception {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.completeBooking("bk-xyz", 789);
        assertNotNull(envelope);
        final String json = new String(envelope.openBroadcast(), StandardCharsets.UTF_8);
        assertTrue("Payload should contain provider_id", json.contains("\"provider_id\":789"));
    }

    // 10. Envelope appId is 0x4242
    @Test
    public void testAppIdInEnvelope() {
        final MeshKitBuzzBuzz buzzbuzz = new MeshKitBuzzBuzz(ENDPOINT, (url, body) -> 200);
        final MeshKitEnvelope envelope = buzzbuzz.acceptJob("jr-appid", 123);
        assertNotNull(envelope);
        assertEquals(MeshKitBuzzBuzz.APP_ID, envelope.getAppId());
    }
}
