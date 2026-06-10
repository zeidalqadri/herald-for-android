//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

public class MeshKitIdentityTest {

    @Test
    public void testKeyGeneration() {
        final MeshKitIdentity identity = new MeshKitIdentity();
        assertEquals(32, identity.getSigningPublicKey().length);
        assertEquals(32, identity.getAgreementPublicKey().length);
        assertEquals(32, identity.getSeed().length);
    }

    @Test
    public void testSeedRoundtrip() {
        final MeshKitIdentity identity1 = new MeshKitIdentity();
        final byte[] seed = identity1.getSeed();
        final MeshKitIdentity identity2 = new MeshKitIdentity(seed);

        assertArrayEquals(identity1.getSigningPublicKey(), identity2.getSigningPublicKey());
        assertArrayEquals(identity1.getAgreementPublicKey(), identity2.getAgreementPublicKey());
    }

    @Test
    public void testSignAndVerify() {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final byte[] data = "hello meshkit".getBytes();
        final byte[] signature = identity.sign(data);

        assertEquals(64, signature.length);
        assertTrue(MeshKitIdentity.verify(signature, data, identity.getSigningPublicKey()));
    }

    @Test
    public void testSignatureFailsWithWrongKey() {
        final MeshKitIdentity identity1 = new MeshKitIdentity();
        final MeshKitIdentity identity2 = new MeshKitIdentity();
        final byte[] data = "hello meshkit".getBytes();
        final byte[] signature = identity1.sign(data);

        assertFalse(MeshKitIdentity.verify(signature, data, identity2.getSigningPublicKey()));
    }

    @Test
    public void testSignatureFailsWithTamperedData() {
        final MeshKitIdentity identity = new MeshKitIdentity();
        final byte[] data = "hello meshkit".getBytes();
        final byte[] signature = identity.sign(data);

        final byte[] tampered = "hello TAMPERED".getBytes();
        assertFalse(MeshKitIdentity.verify(signature, tampered, identity.getSigningPublicKey()));
    }

    @Test
    public void testSharedSecretAgreement() {
        final MeshKitIdentity alice = new MeshKitIdentity();
        final MeshKitIdentity bob = new MeshKitIdentity();

        final byte[] secretAB = alice.sharedSecret(bob.getAgreementPublicKey());
        final byte[] secretBA = bob.sharedSecret(alice.getAgreementPublicKey());

        assertEquals(32, secretAB.length);
        assertArrayEquals(secretAB, secretBA);
    }

    @Test
    public void testDeriveSymmetricKey() {
        final MeshKitIdentity alice = new MeshKitIdentity();
        final MeshKitIdentity bob = new MeshKitIdentity();
        final byte[] salt = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                        0x09, 0x0A, 0x0B, 0x0C};

        final byte[] keyAB = alice.deriveSymmetricKey(bob.getAgreementPublicKey(), salt);
        final byte[] keyBA = bob.deriveSymmetricKey(alice.getAgreementPublicKey(), salt);

        assertEquals(32, keyAB.length);
        assertArrayEquals(keyAB, keyBA);

        // Same inputs produce same output (deterministic)
        final byte[] keyAB2 = alice.deriveSymmetricKey(bob.getAgreementPublicKey(), salt);
        assertArrayEquals(keyAB, keyAB2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidSeedLength() {
        new MeshKitIdentity(new byte[16]);
    }
}
