//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.heraldprox.herald.sensor.Device;
import io.heraldprox.herald.sensor.PayloadDataSupplier;
import io.heraldprox.herald.sensor.datatype.Data;
import io.heraldprox.herald.sensor.datatype.LegacyPayloadData;
import io.heraldprox.herald.sensor.datatype.PayloadData;
import io.heraldprox.herald.sensor.datatype.PayloadTimestamp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MeshKit payload data supplier. Bridges Herald BLE transport with MeshKit identity.
 *
 * V1 payload: [version:1][node_id:16][timestamp:4] = 21 bytes (plaintext)
 * V2 payload: [version:1][nonce:12][ciphertext:16][timestamp:4] = 33 bytes (AES-128-CTR encrypted)
 *
 * Encryption uses AES-128 in CTR mode (the encryption step of AES-GCM without the auth tag).
 * The 12-byte nonce is random per payload; IV = nonce || 0x00000000.
 */
public class MeshKitPayloadSupplier implements PayloadDataSupplier {
    public static final int PAYLOAD_LENGTH_V1 = 21;
    public static final int PAYLOAD_LENGTH_V2 = 33;
    public static final byte VERSION_V1 = 1;
    public static final byte VERSION_V2 = 2;

    // Legacy constants for backward compatibility
    public static final int PAYLOAD_LENGTH = PAYLOAD_LENGTH_V1;
    public static final byte VERSION = VERSION_V1;

    @NonNull
    private final UUID nodeId;

    /** 16-byte AES-128 encryption key. null = unencrypted (v1). */
    @Nullable
    private final byte[] encryptionKey;

    private final SecureRandom secureRandom = new SecureRandom();

    public MeshKitPayloadSupplier(@NonNull final UUID nodeId, @Nullable final byte[] encryptionKey) {
        if (encryptionKey != null && encryptionKey.length != 16) {
            throw new IllegalArgumentException("Encryption key must be 16 bytes");
        }
        this.nodeId = nodeId;
        this.encryptionKey = encryptionKey != null ? Arrays.copyOf(encryptionKey, 16) : null;
    }

    public MeshKitPayloadSupplier(@NonNull final UUID nodeId) {
        this(nodeId, null);
    }

    public MeshKitPayloadSupplier() {
        this(UUID.randomUUID(), null);
    }

    @NonNull
    public UUID getNodeId() {
        return nodeId;
    }

    /** Current payload length based on encryption mode. */
    public int getCurrentPayloadLength() {
        return encryptionKey != null ? PAYLOAD_LENGTH_V2 : PAYLOAD_LENGTH_V1;
    }

    /** Current version based on encryption mode. */
    public byte getCurrentVersion() {
        return encryptionKey != null ? VERSION_V2 : VERSION_V1;
    }

    // MARK:- PayloadDataSupplier

    @Nullable
    @Override
    public LegacyPayloadData legacyPayload(@NonNull final PayloadTimestamp timestamp, @Nullable final Device device) {
        return null;
    }

    @NonNull
    @Override
    public PayloadData payload(@NonNull final PayloadTimestamp timestamp, @Nullable final Device device) {
        if (encryptionKey != null) {
            final PayloadData encrypted = encryptedPayload(timestamp, encryptionKey);
            if (encrypted != null) return encrypted;
        }
        return unencryptedPayload(timestamp);
    }

    @NonNull
    @Override
    public List<PayloadData> payload(@NonNull final Data data) {
        final int length = getCurrentPayloadLength();
        final List<PayloadData> payloads = new ArrayList<>();
        final byte[] bytes = data.value;
        for (int index = 0; (index + length) <= bytes.length; index += length) {
            final byte[] payloadBytes = new byte[length];
            System.arraycopy(bytes, index, payloadBytes, 0, length);
            payloads.add(new PayloadData(payloadBytes));
        }
        return payloads;
    }

    // MARK:- V1 Payload (plaintext)

    @NonNull
    private PayloadData unencryptedPayload(@NonNull final PayloadTimestamp timestamp) {
        final byte[] bytes = new byte[PAYLOAD_LENGTH_V1];
        bytes[0] = VERSION_V1;
        encodeNodeId(bytes, 1);
        encodeTimestamp(bytes, 17, timestamp);
        return new PayloadData(bytes);
    }

    // MARK:- V2 Payload (encrypted)

    @Nullable
    private PayloadData encryptedPayload(@NonNull final PayloadTimestamp timestamp, @NonNull final byte[] key) {
        // Generate 12-byte random nonce
        final byte[] nonce = new byte[12];
        secureRandom.nextBytes(nonce);

        // Encrypt node_id
        final byte[] plainNodeId = new byte[16];
        encodeNodeId(plainNodeId, 0);
        final byte[] ciphertext = aesCTRCrypt(plainNodeId, key, nonce);
        if (ciphertext == null) return null;

        final byte[] bytes = new byte[PAYLOAD_LENGTH_V2];
        bytes[0] = VERSION_V2;
        System.arraycopy(nonce, 0, bytes, 1, 12);
        System.arraycopy(ciphertext, 0, bytes, 13, 16);
        encodeTimestamp(bytes, 29, timestamp);
        return new PayloadData(bytes);
    }

    // MARK:- Encoding helpers

    private void encodeNodeId(@NonNull final byte[] dest, final int offset) {
        final long msb = nodeId.getMostSignificantBits();
        final long lsb = nodeId.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            dest[offset + i] = (byte) ((msb >>> (56 - 8 * i)) & 0xFF);
            dest[offset + 8 + i] = (byte) ((lsb >>> (56 - 8 * i)) & 0xFF);
        }
    }

    private void encodeTimestamp(@NonNull final byte[] dest, final int offset, @NonNull final PayloadTimestamp timestamp) {
        final long epoch = timestamp.value.getTime() / 1000;
        dest[offset] = (byte) (epoch & 0xFF);
        dest[offset + 1] = (byte) ((epoch >>> 8) & 0xFF);
        dest[offset + 2] = (byte) ((epoch >>> 16) & 0xFF);
        dest[offset + 3] = (byte) ((epoch >>> 24) & 0xFF);
    }

    // MARK:- Parsing (static, v1 only)

    /** Extract node ID from a v1 (plaintext) MeshKit payload. */
    @Nullable
    public static UUID parseNodeId(@NonNull final PayloadData payload) {
        if (payload.value.length != PAYLOAD_LENGTH_V1) return null;
        if (payload.value[0] != VERSION_V1) return null;
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (payload.value[1 + i] & 0xFFL);
            lsb = (lsb << 8) | (payload.value[9 + i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }

    /** Extract timestamp from a v1 (plaintext) MeshKit payload. */
    @Nullable
    public static Date parseTimestamp(@NonNull final PayloadData payload) {
        if (payload.value.length != PAYLOAD_LENGTH_V1) return null;
        return decodeTimestamp(payload.value, 17);
    }

    // MARK:- Parsing (instance, v1 + v2)

    /** Extract node ID from any MeshKit payload version. Uses encryption key for v2. */
    @Nullable
    public UUID decryptNodeId(@NonNull final PayloadData payload) {
        if (payload.value.length == 0) return null;
        final byte ver = payload.value[0];

        if (ver == VERSION_V1) {
            return parseNodeId(payload);
        } else if (ver == VERSION_V2) {
            if (payload.value.length != PAYLOAD_LENGTH_V2) return null;
            if (encryptionKey == null) return null;
            final byte[] nonce = Arrays.copyOfRange(payload.value, 1, 13);
            final byte[] ciphertext = Arrays.copyOfRange(payload.value, 13, 29);
            final byte[] plaintext = aesCTRCrypt(ciphertext, encryptionKey, nonce);
            if (plaintext == null || plaintext.length != 16) return null;
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (plaintext[i] & 0xFFL);
                lsb = (lsb << 8) | (plaintext[8 + i] & 0xFFL);
            }
            return new UUID(msb, lsb);
        }
        return null;
    }

    /** Extract timestamp from any MeshKit payload version. */
    @Nullable
    public Date decryptTimestamp(@NonNull final PayloadData payload) {
        if (payload.value.length == 0) return null;
        final byte ver = payload.value[0];

        if (ver == VERSION_V1) {
            return parseTimestamp(payload);
        } else if (ver == VERSION_V2) {
            if (payload.value.length != PAYLOAD_LENGTH_V2) return null;
            return decodeTimestamp(payload.value, 29);
        }
        return null;
    }

    @Nullable
    private static Date decodeTimestamp(@NonNull final byte[] bytes, final int offset) {
        final long epoch =
                (bytes[offset] & 0xFFL) |
                ((bytes[offset + 1] & 0xFFL) << 8) |
                ((bytes[offset + 2] & 0xFFL) << 16) |
                ((bytes[offset + 3] & 0xFFL) << 24);
        return new Date(epoch * 1000);
    }

    // MARK:- AES-128-CTR

    /** AES-128 CTR encrypt/decrypt (symmetric). IV = nonce(12) || 0x00000000(4). */
    @Nullable
    static byte[] aesCTRCrypt(@NonNull final byte[] input, @NonNull final byte[] key, @NonNull final byte[] nonce) {
        if (key.length != 16 || nonce.length != 12) return null;
        try {
            final byte[] iv = new byte[16];
            System.arraycopy(nonce, 0, iv, 0, 12);
            // Last 4 bytes are zero (initial counter)
            final Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (final Exception e) {
            return null;
        }
    }
}
