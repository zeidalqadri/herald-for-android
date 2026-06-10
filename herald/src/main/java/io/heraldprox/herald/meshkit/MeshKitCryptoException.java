//  Copyright 2026 MeshKit Contributors
//  SPDX-License-Identifier: Apache-2.0

package io.heraldprox.herald.meshkit;

/**
 * Exception thrown when MeshKit envelope crypto operations fail.
 */
public class MeshKitCryptoException extends Exception {
    public MeshKitCryptoException(String message) {
        super(message);
    }

    public MeshKitCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
