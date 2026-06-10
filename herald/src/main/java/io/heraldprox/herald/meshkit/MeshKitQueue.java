//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Bounded, thread-safe in-memory store-and-forward queue for {@link MeshKitEnvelope}.
 * Envelopes are auto-evicted when their TTL (wall-clock age) expires, or when the queue
 * is at capacity (oldest entry dropped first).
 */
public class MeshKitQueue {

    public static final int DEFAULT_CAPACITY = 200;
    public static final long DEFAULT_TTL_MS = 24L * 60 * 60 * 1000; // 24 hours

    private final int capacity;
    private final long ttlMs;
    private final List<Entry> entries = new ArrayList<>();
    private final Object lock = new Object();

    private static final class Entry {
        final MeshKitEnvelope envelope;
        final long enqueuedAtMs;

        Entry(final MeshKitEnvelope envelope) {
            this.envelope = envelope;
            this.enqueuedAtMs = System.currentTimeMillis();
        }

        boolean isExpired(final long ttlMs) {
            return System.currentTimeMillis() - enqueuedAtMs > ttlMs;
        }
    }

    /** Listener notified when a detected peer has pending envelopes. */
    public interface MeshKitDeliveryListener {
        void onShouldDeliver(List<MeshKitEnvelope> envelopes, UUID toPeerNodeId);
    }

    /** Predicate for selective removal. */
    public interface EnvelopePredicate {
        boolean test(MeshKitEnvelope envelope);
    }

    public MeshKitQueue() {
        this(DEFAULT_CAPACITY, DEFAULT_TTL_MS);
    }

    public MeshKitQueue(final int capacity, final long ttlMs) {
        this.capacity = capacity;
        this.ttlMs = ttlMs;
    }

    /**
     * Enqueue an envelope. Evicts expired entries first; if still at capacity, drops the
     * oldest entry. Always returns true.
     */
    public boolean enqueue(final MeshKitEnvelope envelope) {
        synchronized (lock) {
            evictExpired();
            if (entries.size() >= capacity) {
                entries.remove(0); // drop oldest
            }
            entries.add(new Entry(envelope));
        }
        return true;
    }

    /**
     * Remove and return envelopes for a peer: directed envelopes addressed to {@code nodeId}
     * plus broadcast envelopes whose hop count is still below their TTL.
     */
    public List<MeshKitEnvelope> dequeueForPeer(final UUID nodeId) {
        synchronized (lock) {
            evictExpired();
            final List<MeshKitEnvelope> result = new ArrayList<>();
            final Iterator<Entry> it = entries.iterator();
            while (it.hasNext()) {
                final Entry e = it.next();
                if (matchesForPeer(e.envelope, nodeId)) {
                    result.add(e.envelope);
                    it.remove();
                }
            }
            return result;
        }
    }

    /**
     * Peek (without removing) envelopes for a peer: same filter as
     * {@link #dequeueForPeer(UUID)} but leaves them in the queue.
     */
    public List<MeshKitEnvelope> peekForPeer(final UUID nodeId) {
        synchronized (lock) {
            evictExpired();
            final List<MeshKitEnvelope> result = new ArrayList<>();
            for (final Entry e : entries) {
                if (matchesForPeer(e.envelope, nodeId)) {
                    result.add(e.envelope);
                }
            }
            return result;
        }
    }

    /**
     * Remove and return all broadcast envelopes that can still relay (hops &lt; ttl).
     */
    public List<MeshKitEnvelope> dequeueBroadcasts() {
        synchronized (lock) {
            evictExpired();
            final List<MeshKitEnvelope> result = new ArrayList<>();
            final Iterator<Entry> it = entries.iterator();
            while (it.hasNext()) {
                final Entry e = it.next();
                final MeshKitEnvelope env = e.envelope;
                if (env.getToNodeId() == null && canRelay(env)) {
                    result.add(env);
                    it.remove();
                }
            }
            return result;
        }
    }

    /**
     * Remove envelopes matching {@code predicate}. Returns the count removed.
     */
    public int remove(final EnvelopePredicate predicate) {
        synchronized (lock) {
            int count = 0;
            final Iterator<Entry> it = entries.iterator();
            while (it.hasNext()) {
                if (predicate.test(it.next().envelope)) {
                    it.remove();
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Drain all non-expired envelopes. The queue is empty after this call.
     */
    public List<MeshKitEnvelope> drain() {
        synchronized (lock) {
            evictExpired();
            final List<MeshKitEnvelope> result = new ArrayList<>(entries.size());
            for (final Entry e : entries) {
                result.add(e.envelope);
            }
            entries.clear();
            return result;
        }
    }

    public int getCount() {
        synchronized (lock) {
            evictExpired();
            return entries.size();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            evictExpired();
            return entries.isEmpty();
        }
    }

    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    // MARK:- Internal helpers

    /** Must be called with lock held. */
    private void evictExpired() {
        final Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            if (it.next().isExpired(ttlMs)) {
                it.remove();
            }
        }
    }

    /**
     * Returns true if the envelope should be delivered to {@code nodeId}:
     * - Directed envelopes addressed exactly to {@code nodeId}, OR
     * - Broadcast envelopes (null toNodeId) that can still relay (hops &lt; ttl).
     */
    private static boolean matchesForPeer(final MeshKitEnvelope env, final UUID nodeId) {
        if (env.getToNodeId() != null) {
            return env.getToNodeId().equals(nodeId);
        }
        return canRelay(env);
    }

    private static boolean canRelay(final MeshKitEnvelope env) {
        return (env.getHops() & 0xFF) < (env.getTtl() & 0xFF);
    }
}
