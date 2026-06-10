//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import androidx.annotation.NonNull;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * MeshKit identity: Ed25519 signing key pair + X25519 agreement key pair,
 * both derived from the same 32-byte seed.
 */
public class MeshKitIdentity {

    private static final byte[] HKDF_INFO = "meshkit-v1".getBytes(StandardCharsets.UTF_8);

    @NonNull
    private final byte[] seed;

    @NonNull
    private final Ed25519PrivateKeyParameters signingKey;

    @NonNull
    private final X25519PrivateKeyParameters agreementKey;

    /**
     * Generate a new identity from a fresh random seed.
     */
    public MeshKitIdentity() {
        this(generateSeed());
    }

    /**
     * Restore an identity from an existing 32-byte seed.
     */
    public MeshKitIdentity(@NonNull final byte[] seed) {
        if (seed.length != 32) {
            throw new IllegalArgumentException("Seed must be 32 bytes");
        }
        this.seed = Arrays.copyOf(seed, 32);
        this.signingKey = new Ed25519PrivateKeyParameters(seed, 0);
        this.agreementKey = new X25519PrivateKeyParameters(seed, 0);
    }

    /**
     * Returns a copy of the 32-byte seed.
     */
    @NonNull
    public byte[] getSeed() {
        return Arrays.copyOf(seed, 32);
    }

    /**
     * Returns the 32-byte Ed25519 public key for signature verification.
     */
    @NonNull
    public byte[] getSigningPublicKey() {
        return signingKey.generatePublicKey().getEncoded();
    }

    /**
     * Returns the 32-byte X25519 public key for key agreement.
     */
    @NonNull
    public byte[] getAgreementPublicKey() {
        return agreementKey.generatePublicKey().getEncoded();
    }

    /**
     * Sign data with the Ed25519 private key.
     *
     * @return 64-byte signature
     */
    @NonNull
    public byte[] sign(@NonNull final byte[] data) {
        final Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, signingKey);
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    /**
     * Verify an Ed25519 signature against data and a public key.
     *
     * @param signature 64-byte Ed25519 signature
     * @param data the signed data
     * @param publicKey 32-byte Ed25519 public key
     * @return true if the signature is valid
     */
    public static boolean verify(@NonNull final byte[] signature, @NonNull final byte[] data,
                                 @NonNull final byte[] publicKey) {
        final Ed25519PublicKeyParameters pubKey = new Ed25519PublicKeyParameters(publicKey, 0);
        final Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, pubKey);
        verifier.update(data, 0, data.length);
        return verifier.verifySignature(signature);
    }

    /**
     * Compute 32-byte X25519 shared secret with a peer's agreement public key.
     */
    @NonNull
    public byte[] sharedSecret(@NonNull final byte[] peerAgreementPublicKey) {
        final X25519Agreement agreement = new X25519Agreement();
        agreement.init(agreementKey);
        final byte[] secret = new byte[agreement.getAgreementSize()];
        final X25519PublicKeyParameters peerKey = new X25519PublicKeyParameters(peerAgreementPublicKey, 0);
        agreement.calculateAgreement(peerKey, secret, 0);
        return secret;
    }

    /**
     * Derive a 32-byte symmetric key from ECDH shared secret using HKDF-SHA256.
     */
    @NonNull
    public byte[] deriveSymmetricKey(@NonNull final byte[] peerAgreementPublicKey,
                                     @NonNull final byte[] salt) {
        final byte[] secret = sharedSecret(peerAgreementPublicKey);
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(secret, salt, HKDF_INFO));
        final byte[] key = new byte[32];
        hkdf.generateBytes(key, 0, 32);
        return key;
    }

    @NonNull
    private static byte[] generateSeed() {
        final byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }
}
