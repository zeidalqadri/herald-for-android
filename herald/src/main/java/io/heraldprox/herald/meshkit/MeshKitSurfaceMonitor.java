// Copyright 2026 MeshKit Contributors
// SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Monitors network connectivity and drains the {@link MeshKitQueue} when internet
 * returns after being offline ("surface flush").
 *
 * <p>Uses {@link ConnectivityManager.NetworkCallback} (API 21+). Call {@link #start(Context)}
 * to begin monitoring and {@link #stop(Context)} to clean up.
 */
public class MeshKitSurfaceMonitor {

    /** Called when internet connectivity returns after being offline. */
    public interface MeshKitSurfaceListener {
        void onSurface(@NonNull List<MeshKitEnvelope> envelopes);
    }

    @NonNull
    private final MeshKitQueue queue;

    @Nullable
    private volatile MeshKitSurfaceListener listener;

    @Nullable
    private ConnectivityManager.NetworkCallback networkCallback;

    private volatile boolean wasOffline = false;
    private volatile boolean isRunning = false;
    private volatile boolean isConnected = false;

    public MeshKitSurfaceMonitor(@NonNull final MeshKitQueue queue) {
        this.queue = queue;
    }

    public void setListener(@Nullable final MeshKitSurfaceListener listener) {
        this.listener = listener;
    }

    /**
     * Start monitoring connectivity. Safe to call multiple times (idempotent).
     */
    public synchronized void start(@NonNull final Context context) {
        if (isRunning) return;

        final ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        networkCallback = buildCallback();
        final NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cm.registerNetworkCallback(request, networkCallback);
        isRunning = true;
    }

    /**
     * Stop monitoring. Safe to call multiple times.
     */
    public synchronized void stop(@NonNull final Context context) {
        if (!isRunning || networkCallback == null) return;

        final ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
        }
        networkCallback = null;
        isRunning = false;
    }

    /** Returns the last known connectivity state. */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Package-private: simulates a connectivity surface event for testing.
     * Drains the queue and fires the listener if there are envelopes.
     */
    void simulateSurface() {
        final List<MeshKitEnvelope> envelopes = queue.drain();
        if (envelopes.isEmpty()) return;
        final MeshKitSurfaceListener l = listener;
        if (l != null) {
            l.onSurface(envelopes);
        }
    }

    @NonNull
    private ConnectivityManager.NetworkCallback buildCallback() {
        return new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull final Network network) {
                isConnected = true;
                if (wasOffline) {
                    wasOffline = false;
                    simulateSurface();
                }
            }

            @Override
            public void onLost(@NonNull final Network network) {
                isConnected = false;
                wasOffline = true;
            }
        };
    }
}
