//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Binary envelope format for MeshKit messages. Two modes:
 * <ul>
 *   <li><b>Broadcast</b> (flags=0x00): signed plaintext</li>
 *   <li><b>Directed</b> (flags=0x03): signed + encrypted (ChaCha20-Poly1305)</li>
 * </ul>
 *
 * Wire format is byte-for-byte compatible with the iOS Swift implementation.
 */
public class MeshKitEnvelope {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static final byte VERSION = 1;
    public static final int MAX_PAYLOAD_SIZE = 400;

    // Flags
    public static final byte FLAG_DIRECTED = 0x01;
    public static final byte FLAG_ENCRYPTED = 0x02;

    private static final byte[] HKDF_INFO = "meshkit-v1".getBytes(StandardCharsets.UTF_8);
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int SIGNATURE_LENGTH = 64;
    private static final int PUBLIC_KEY_LENGTH = 32;
    private static final int NODE_ID_LENGTH = 16;

    private final byte flags;
    private final short appId;
    @NonNull
    private final UUID fromNodeId;
    @NonNull
    private final byte[] fromSigningPublicKey;
    @Nullable
    private final UUID toNodeId;
    @Nullable
    private final byte[] ephemeralPublicKey;
    private final int timestamp;
    private final byte ttl;
    private byte hops;
    @Nullable
    private final byte[] nonce;
    @NonNull
    private final byte[] payload;
    @Nullable
    private final byte[] tag;
    @NonNull
    private final byte[] signature;

    private MeshKitEnvelope(final byte flags, final short appId, @NonNull final UUID fromNodeId,
                            @NonNull final byte[] fromSigningPublicKey, @Nullable final UUID toNodeId,
                            @Nullable final byte[] ephemeralPublicKey, final int timestamp,
                            final byte ttl, final byte hops, @Nullable final byte[] nonce,
                            @NonNull final byte[] payload, @Nullable final byte[] tag,
                            @NonNull final byte[] signature) {
        this.flags = flags;
        this.appId = appId;
        this.fromNodeId = fromNodeId;
        this.fromSigningPublicKey = fromSigningPublicKey;
        this.toNodeId = toNodeId;
        this.ephemeralPublicKey = ephemeralPublicKey;
        this.timestamp = timestamp;
        this.ttl = ttl;
        this.hops = hops;
        this.nonce = nonce;
        this.payload = payload;
        this.tag = tag;
        this.signature = signature;
    }

    // MARK:- Seal

    /**
     * Create a signed broadcast envelope.
     */
    @NonNull
    public static MeshKitEnvelope sealBroadcast(@NonNull final byte[] payload, final int appId,
                                                 @NonNull final UUID fromNodeId,
                                                 @NonNull final MeshKitIdentity identity,
                                                 final int ttl) throws MeshKitCryptoException {
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new MeshKitCryptoException("Payload exceeds maximum size of " + MAX_PAYLOAD_SIZE);
        }
        final byte flags = 0x00;
        final int ts = (int) (System.currentTimeMillis() / 1000);
        final byte[] signingPubKey = identity.getSigningPublicKey();

        // Build signable data (everything except hops and signature)
        final byte[] signable = buildSignableBroadcast(flags, (short) appId, fromNodeId,
                signingPubKey, ts, (byte) ttl, payload);
        final byte[] sig = identity.sign(signable);

        return new MeshKitEnvelope(flags, (short) appId, fromNodeId, signingPubKey, null, null,
                ts, (byte) ttl, (byte) 0, null, payload, null, sig);
    }

    /**
     * Create a signed and encrypted directed envelope.
     */
    @NonNull
    public static MeshKitEnvelope sealDirected(@NonNull final byte[] payload, final int appId,
                                                @NonNull final UUID fromNodeId,
                                                @NonNull final MeshKitIdentity identity,
                                                @NonNull final UUID toNodeId,
                                                @NonNull final byte[] recipientAgreementPublicKey,
                                                final int ttl) throws MeshKitCryptoException {
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new MeshKitCryptoException("Payload exceeds maximum size of " + MAX_PAYLOAD_SIZE);
        }
        final byte flags = FLAG_DIRECTED | FLAG_ENCRYPTED;
        final int ts = (int) (System.currentTimeMillis() / 1000);
        final byte[] signingPubKey = identity.getSigningPublicKey();

        // Generate ephemeral X25519 key pair for forward secrecy
        final byte[] ephSeed = new byte[32];
        new SecureRandom().nextBytes(ephSeed);
        final X25519PrivateKeyParameters ephPriv = new X25519PrivateKeyParameters(ephSeed, 0);
        final byte[] ephPub = ephPriv.generatePublicKey().getEncoded();

        // ECDH with ephemeral private + recipient public
        final X25519Agreement agreement = new X25519Agreement();
        agreement.init(ephPriv);
        final byte[] secret = new byte[agreement.getAgreementSize()];
        final X25519PublicKeyParameters peerKey = new X25519PublicKeyParameters(recipientAgreementPublicKey, 0);
        agreement.calculateAgreement(peerKey, secret, 0);

        // HKDF to derive symmetric key
        final byte[] nonce = new byte[NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        final byte[] salt = nonce;
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(secret, salt, HKDF_INFO));
        final byte[] symmetricKey = new byte[32];
        hkdf.generateBytes(symmetricKey, 0, 32);

        // Encrypt with ChaCha20-Poly1305
        final byte[] ciphertextAndTag = chaChaEncrypt(symmetricKey, nonce, payload);
        if (ciphertextAndTag == null) {
            throw new MeshKitCryptoException("ChaCha20-Poly1305 encryption failed");
        }
        final byte[] ciphertext = Arrays.copyOfRange(ciphertextAndTag, 0, ciphertextAndTag.length - TAG_LENGTH);
        final byte[] authTag = Arrays.copyOfRange(ciphertextAndTag, ciphertextAndTag.length - TAG_LENGTH, ciphertextAndTag.length);

        // Build signable data (everything except hops and signature)
        final byte[] signable = buildSignableDirected(flags, (short) appId, fromNodeId,
                signingPubKey, toNodeId, ephPub, ts, (byte) ttl, nonce, ciphertext, authTag);
        final byte[] sig = identity.sign(signable);

        return new MeshKitEnvelope(flags, (short) appId, fromNodeId, signingPubKey, toNodeId,
                ephPub, ts, (byte) ttl, (byte) 0, nonce, ciphertext, authTag, sig);
    }

    // MARK:- Open

    /**
     * Open a broadcast envelope: verify signature and return payload.
     */
    @NonNull
    public byte[] openBroadcast() throws MeshKitCryptoException {
        if ((flags & FLAG_ENCRYPTED) != 0) {
            throw new MeshKitCryptoException("Envelope is encrypted, use openDirected()");
        }
        if (!verifySignature()) {
            throw new MeshKitCryptoException("Signature verification failed");
        }
        return Arrays.copyOf(payload, payload.length);
    }

    /**
     * Open a directed envelope: verify signature and decrypt payload.
     */
    @NonNull
    public byte[] openDirected(@NonNull final MeshKitIdentity identity) throws MeshKitCryptoException {
        if ((flags & FLAG_ENCRYPTED) == 0) {
            throw new MeshKitCryptoException("Envelope is not encrypted, use openBroadcast()");
        }
        if (!verifySignature()) {
            throw new MeshKitCryptoException("Signature verification failed");
        }
        if (ephemeralPublicKey == null || nonce == null || tag == null) {
            throw new MeshKitCryptoException("Missing encryption fields");
        }

        // ECDH with recipient private + ephemeral public
        final byte[] secret = identity.sharedSecret(ephemeralPublicKey);

        // HKDF to derive symmetric key (using nonce as salt, same as seal)
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(secret, nonce, HKDF_INFO));
        final byte[] symmetricKey = new byte[32];
        hkdf.generateBytes(symmetricKey, 0, 32);

        // Reconstruct ciphertext+tag for decryption
        final byte[] ciphertextAndTag = new byte[payload.length + TAG_LENGTH];
        System.arraycopy(payload, 0, ciphertextAndTag, 0, payload.length);
        System.arraycopy(tag, 0, ciphertextAndTag, payload.length, TAG_LENGTH);

        final byte[] plaintext = chaChaDecrypt(symmetricKey, nonce, ciphertextAndTag);
        if (plaintext == null) {
            throw new MeshKitCryptoException("ChaCha20-Poly1305 decryption failed");
        }
        return plaintext;
    }

    // MARK:- Verify

    /**
     * Verify the Ed25519 signature. The signature covers everything except hops and the signature itself.
     */
    public boolean verifySignature() {
        final byte[] signable;
        if ((flags & FLAG_ENCRYPTED) != 0) {
            signable = buildSignableDirected(flags, appId, fromNodeId, fromSigningPublicKey,
                    toNodeId, ephemeralPublicKey, timestamp, ttl, nonce, payload, tag);
        } else {
            signable = buildSignableBroadcast(flags, appId, fromNodeId, fromSigningPublicKey,
                    timestamp, ttl, payload);
        }
        return MeshKitIdentity.verify(signature, signable, fromSigningPublicKey);
    }

    // MARK:- Relay

    /**
     * Increment hops. Returns true if the envelope can continue relaying (hops < ttl).
     */
    public boolean relay() {
        hops++;
        return (hops & 0xFF) < (ttl & 0xFF);
    }

    // MARK:- Serialize

    /**
     * Serialize to wire format.
     */
    @NonNull
    public byte[] serialize() {
        final boolean directed = (flags & FLAG_DIRECTED) != 0;
        final boolean encrypted = (flags & FLAG_ENCRYPTED) != 0;

        int size = 1 + 1 + 2 + NODE_ID_LENGTH + PUBLIC_KEY_LENGTH; // version, flags, appId, fromNodeId, fromPubKey
        if (directed) {
            size += NODE_ID_LENGTH; // toNodeId
        }
        if (encrypted) {
            size += PUBLIC_KEY_LENGTH; // ephemeralPublicKey
        }
        size += 4 + 1 + 1 + 2; // timestamp, ttl, hops, payload_len
        if (encrypted) {
            size += NONCE_LENGTH; // nonce
        }
        size += payload.length; // payload or ciphertext
        if (encrypted) {
            size += TAG_LENGTH; // tag
        }
        size += SIGNATURE_LENGTH; // signature

        final ByteBuffer buf = ByteBuffer.allocate(size);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.put(VERSION);
        buf.put(flags);
        buf.putShort(appId);

        // Node IDs: big-endian (MSB first, LSB second)
        encodeUUID(buf, fromNodeId);
        buf.put(fromSigningPublicKey);

        if (directed && toNodeId != null) {
            encodeUUID(buf, toNodeId);
        }
        if (encrypted && ephemeralPublicKey != null) {
            buf.put(ephemeralPublicKey);
        }

        buf.putInt(timestamp);
        buf.put(ttl);
        buf.put(hops);
        buf.putShort((short) payload.length);

        if (encrypted && nonce != null) {
            buf.put(nonce);
        }
        buf.put(payload);
        if (encrypted && tag != null) {
            buf.put(tag);
        }
        buf.put(signature);

        return buf.array();
    }

    /**
     * Deserialize from wire format.
     */
    @NonNull
    public static MeshKitEnvelope deserialize(@NonNull final byte[] data) throws MeshKitCryptoException {
        if (data.length < 6) {
            throw new MeshKitCryptoException("Data too short for envelope header");
        }

        final ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        final byte version = buf.get();
        if (version != VERSION) {
            throw new MeshKitCryptoException("Unsupported envelope version: " + version);
        }

        final byte flags = buf.get();
        final short appId = buf.getShort();

        final boolean directed = (flags & FLAG_DIRECTED) != 0;
        final boolean encrypted = (flags & FLAG_ENCRYPTED) != 0;

        final UUID fromNodeId = decodeUUID(buf);
        final byte[] fromPubKey = new byte[PUBLIC_KEY_LENGTH];
        buf.get(fromPubKey);

        UUID toNodeId = null;
        if (directed) {
            toNodeId = decodeUUID(buf);
        }

        byte[] ephPub = null;
        if (encrypted) {
            ephPub = new byte[PUBLIC_KEY_LENGTH];
            buf.get(ephPub);
        }

        final int timestamp = buf.getInt();
        final byte ttl = buf.get();
        final byte hops = buf.get();
        final short payloadLen = buf.getShort();

        byte[] nonce = null;
        if (encrypted) {
            nonce = new byte[NONCE_LENGTH];
            buf.get(nonce);
        }

        final byte[] payload = new byte[payloadLen & 0xFFFF];
        buf.get(payload);

        byte[] authTag = null;
        if (encrypted) {
            authTag = new byte[TAG_LENGTH];
            buf.get(authTag);
        }

        final byte[] signature = new byte[SIGNATURE_LENGTH];
        buf.get(signature);

        return new MeshKitEnvelope(flags, appId, fromNodeId, fromPubKey, toNodeId, ephPub,
                timestamp, ttl, hops, nonce, payload, authTag, signature);
    }

    // MARK:- Getters

    public byte getFlags() { return flags; }
    public int getAppId() { return appId & 0xFFFF; }
    @NonNull public UUID getFromNodeId() { return fromNodeId; }
    @NonNull public byte[] getFromSigningPublicKey() { return Arrays.copyOf(fromSigningPublicKey, fromSigningPublicKey.length); }
    @Nullable public UUID getToNodeId() { return toNodeId; }
    @Nullable public byte[] getEphemeralPublicKey() { return ephemeralPublicKey != null ? Arrays.copyOf(ephemeralPublicKey, ephemeralPublicKey.length) : null; }
    public int getTimestamp() { return timestamp; }
    public byte getTtl() { return ttl; }
    public byte getHops() { return hops; }

    // MARK:- Internal helpers

    private static void encodeUUID(@NonNull final ByteBuffer buf, @NonNull final UUID uuid) {
        // Big-endian: MSB first, LSB second (matching encodeNodeId in MeshKitPayloadSupplier)
        final long msb = uuid.getMostSignificantBits();
        final long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            buf.put((byte) ((msb >>> (56 - 8 * i)) & 0xFF));
        }
        for (int i = 0; i < 8; i++) {
            buf.put((byte) ((lsb >>> (56 - 8 * i)) & 0xFF));
        }
    }

    @NonNull
    private static UUID decodeUUID(@NonNull final ByteBuffer buf) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (buf.get() & 0xFFL);
        }
        for (int i = 0; i < 8; i++) {
            lsb = (lsb << 8) | (buf.get() & 0xFFL);
        }
        return new UUID(msb, lsb);
    }

    /**
     * Encode a UUID to big-endian bytes (16 bytes).
     */
    @NonNull
    private static byte[] uuidToBytes(@NonNull final UUID uuid) {
        final byte[] bytes = new byte[NODE_ID_LENGTH];
        final long msb = uuid.getMostSignificantBits();
        final long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((msb >>> (56 - 8 * i)) & 0xFF);
            bytes[8 + i] = (byte) ((lsb >>> (56 - 8 * i)) & 0xFF);
        }
        return bytes;
    }

    /**
     * Build the signable region for a broadcast envelope.
     * Includes everything except hops and the signature.
     */
    @NonNull
    private static byte[] buildSignableBroadcast(final byte flags, final short appId,
                                                  @NonNull final UUID fromNodeId,
                                                  @NonNull final byte[] fromPubKey,
                                                  final int timestamp, final byte ttl,
                                                  @NonNull final byte[] payload) {
        // version(1) + flags(1) + appId(2) + fromNodeId(16) + fromPubKey(32) + ts(4) + ttl(1) + payloadLen(2) + payload(N)
        final int size = 1 + 1 + 2 + NODE_ID_LENGTH + PUBLIC_KEY_LENGTH + 4 + 1 + 2 + payload.length;
        final ByteBuffer buf = ByteBuffer.allocate(size);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(VERSION);
        buf.put(flags);
        buf.putShort(appId);
        buf.put(uuidToBytes(fromNodeId));
        buf.put(fromPubKey);
        buf.putInt(timestamp);
        buf.put(ttl);
        buf.putShort((short) payload.length);
        buf.put(payload);
        return buf.array();
    }

    /**
     * Build the signable region for a directed envelope.
     * Includes everything except hops and the signature.
     */
    @NonNull
    private static byte[] buildSignableDirected(final byte flags, final short appId,
                                                 @NonNull final UUID fromNodeId,
                                                 @NonNull final byte[] fromPubKey,
                                                 @Nullable final UUID toNodeId,
                                                 @Nullable final byte[] ephPub,
                                                 final int timestamp, final byte ttl,
                                                 @Nullable final byte[] nonce,
                                                 @NonNull final byte[] ciphertext,
                                                 @Nullable final byte[] authTag) {
        // version(1) + flags(1) + appId(2) + fromNodeId(16) + fromPubKey(32) + toNodeId(16)
        // + ephPub(32) + ts(4) + ttl(1) + payloadLen(2) + nonce(12) + ciphertext(N) + tag(16)
        int size = 1 + 1 + 2 + NODE_ID_LENGTH + PUBLIC_KEY_LENGTH + NODE_ID_LENGTH
                + PUBLIC_KEY_LENGTH + 4 + 1 + 2 + NONCE_LENGTH + ciphertext.length + TAG_LENGTH;
        final ByteBuffer buf = ByteBuffer.allocate(size);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(VERSION);
        buf.put(flags);
        buf.putShort(appId);
        buf.put(uuidToBytes(fromNodeId));
        buf.put(fromPubKey);
        if (toNodeId != null) buf.put(uuidToBytes(toNodeId));
        if (ephPub != null) buf.put(ephPub);
        buf.putInt(timestamp);
        buf.put(ttl);
        buf.putShort((short) ciphertext.length);
        if (nonce != null) buf.put(nonce);
        buf.put(ciphertext);
        if (authTag != null) buf.put(authTag);
        return buf.array();
    }

    // MARK:- ChaCha20-Poly1305 via JCA (Bouncy Castle provider)

    @Nullable
    private static byte[] chaChaEncrypt(@NonNull final byte[] key, @NonNull final byte[] nonce,
                                         @NonNull final byte[] plaintext) {
        try {
            final Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC");
            final SecretKeySpec keySpec = new SecretKeySpec(key, "ChaCha20");
            final IvParameterSpec ivSpec = new IvParameterSpec(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(plaintext);
        } catch (final Exception e) {
            return null;
        }
    }

    @Nullable
    private static byte[] chaChaDecrypt(@NonNull final byte[] key, @NonNull final byte[] nonce,
                                         @NonNull final byte[] ciphertextAndTag) {
        try {
            final Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC");
            final SecretKeySpec keySpec = new SecretKeySpec(key, "ChaCha20");
            final IvParameterSpec ivSpec = new IvParameterSpec(nonce);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(ciphertextAndTag);
        } catch (final Exception e) {
            return null;
        }
    }
}
