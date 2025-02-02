/*
 * Copyright (c) 2003, 2008, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.security.pkcs11;

import java.security.*;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.*;

import sun.security.pkcs11.wrapper.*;

import static sun.security.pkcs11.TemplateManager.O_GENERATE;
import static sun.security.pkcs11.wrapper.PKCS11Constants.*;

/**
 * KeyGenerator implementation class. This class currently supports
 * DES, DESede, AES, ARCFOUR, and Blowfish.
 *
 * @author  Andreas Sterbenz
 * @since   1.5
 */
final class P11KeyGenerator extends KeyGeneratorSpi {

    // token instance
    private final Token token;

    // algorithm name
    private final String algorithm;

    // mechanism id
    private long mechanism;

    // raw key size in bits, e.g. 64 for DES. Always valid.
    private int keySize;

    // bits of entropy in the key, e.g. 56 for DES. Always valid.
    private int significantKeySize;

    // keyType (CKK_*), needed for TemplateManager call only.
    private long keyType;

    // for determining if both 112 and 168 bits of DESede key lengths
    // are supported.
    private boolean supportBothKeySizes;

    /**
     * Utility method for checking if the specified key size is valid
     * and within the supported range. Return the significant key size
     * upon successful validation.
     * @param keyGenMech the PKCS#11 key generation mechanism.
     * @param keySize the to-be-checked key size for this mechanism.
     * @param token token which provides this mechanism.
     * @return the significant key size (in bits) corresponding to the
     * specified key size.
     * @throws InvalidParameterException if the specified key size is invalid.
     * @throws ProviderException if this mechanism isn't supported by SunPKCS11
     * or underlying native impl.
     */
    static int checkKeySize(long keyGenMech, int keySize, Token token)
        throws InvalidAlgorithmParameterException, ProviderException {
        int sigKeySize;
        switch ((int)keyGenMech) {
            case (int)CKM_DES_KEY_GEN:
                if ((keySize != 64) && (keySize != 56)) {
                    throw new InvalidAlgorithmParameterException
                            ("DES key length must be 56 bits");
                }
                sigKeySize = 56;
                break;
            case (int)CKM_DES2_KEY_GEN:
            case (int)CKM_DES3_KEY_GEN:
                if ((keySize == 112) || (keySize == 128)) {
                    sigKeySize = 112;
                } else if ((keySize == 168) || (keySize == 192)) {
                    sigKeySize = 168;
                } else {
                    throw new InvalidAlgorithmParameterException
                            ("DESede key length must be 112, or 168 bits");
                }
                break;
            default:
                // Handle all variable-key-length algorithms here
                CK_MECHANISM_INFO info = null;
                try {
                    info = token.getMechanismInfo(keyGenMech);
                } catch (PKCS11Exception p11e) {
                    // Should never happen
                    throw new ProviderException
                            ("Cannot retrieve mechanism info", p11e);
                }
                if (info == null) {
                    // XXX Unable to retrieve the supported key length from
                    // the underlying native impl. Skip the checking for now.
                    return keySize;
                }
                // PKCS#11 defines these to be in number of bytes except for
                // RC4 which is in bits. However, some PKCS#11 impls still use
                // bytes for all mechs, e.g. NSS. We try to detect this
                // inconsistency if the minKeySize seems unreasonably small.
                int minKeySize = (int)info.ulMinKeySize;
                int maxKeySize = (int)info.ulMaxKeySize;
                if (keyGenMech != CKM_RC4_KEY_GEN || minKeySize < 8) {
                    minKeySize = (int)info.ulMinKeySize << 3;
                    maxKeySize = (int)info.ulMaxKeySize << 3;
                }
                // Explicitly disallow keys shorter than 40-bits for security
                if (minKeySize < 40) minKeySize = 40;
                if (keySize < minKeySize || keySize > maxKeySize) {
                    throw new InvalidAlgorithmParameterException
                            ("Key length must be between " + minKeySize +
                            " and " + maxKeySize + " bits");
                }
                if (keyGenMech == CKM_AES_KEY_GEN) {
                    if ((keySize != 128) && (keySize != 192) &&
                        (keySize != 256)) {
                        throw new InvalidAlgorithmParameterException
                                ("AES key length must be " + minKeySize +
                                (maxKeySize >= 192? ", 192":"") +
                                (maxKeySize >= 256? ", or 256":"") + " bits");
                    }
                }
                sigKeySize = keySize;
        }
        return sigKeySize;
    }

    P11KeyGenerator(Token token, String algorithm, long mechanism)
            throws PKCS11Exception {
        super();
        this.token = token;
        this.algorithm = algorithm;
        this.mechanism = mechanism;

        if (this.mechanism == CKM_DES3_KEY_GEN) {
            /* Given the current lookup order specified in SunPKCS11.java,
               if CKM_DES2_KEY_GEN is used to construct this object, it
               means that CKM_DES3_KEY_GEN is disabled or unsupported.
            */
            supportBothKeySizes =
                (token.provider.config.isEnabled(CKM_DES2_KEY_GEN) &&
                 (token.getMechanismInfo(CKM_DES2_KEY_GEN) != null));
        }
        setDefaultKeySize();
    }

    // set default keysize and also initialize keyType
    private void setDefaultKeySize() {
        switch ((int)mechanism) {
        case (int)CKM_DES_KEY_GEN:
            keySize = 64;
            keyType = CKK_DES;
            break;
        case (int)CKM_DES2_KEY_GEN:
            keySize = 128;
            keyType = CKK_DES2;
            break;
        case (int)CKM_DES3_KEY_GEN:
            keySize = 192;
            keyType = CKK_DES3;
            break;
        case (int)CKM_AES_KEY_GEN:
            keySize = 128;
            keyType = CKK_AES;
            break;
        case (int)CKM_RC4_KEY_GEN:
            keySize = 128;
            keyType = CKK_RC4;
            break;
        case (int)CKM_BLOWFISH_KEY_GEN:
            keySize = 128;
            keyType = CKK_BLOWFISH;
            break;
        default:
            throw new ProviderException("Unknown mechanism " + mechanism);
        }
        try {
            significantKeySize = checkKeySize(mechanism, keySize, token);
        } catch (InvalidAlgorithmParameterException iape) {
            throw new ProviderException("Unsupported default key size", iape);
        }
    }

    // see JCE spec
    protected void engineInit(SecureRandom random) {
        token.ensureValid();
        setDefaultKeySize();
    }

    // see JCE spec
    protected void engineInit(AlgorithmParameterSpec params,
            SecureRandom random) throws InvalidAlgorithmParameterException {
        throw new InvalidAlgorithmParameterException
                ("AlgorithmParameterSpec not supported");
    }

    // see JCE spec
    protected void engineInit(int keySize, SecureRandom random) {
        token.ensureValid();
        int newSignificantKeySize;
        try {
            newSignificantKeySize = checkKeySize(mechanism, keySize, token);
        } catch (InvalidAlgorithmParameterException iape) {
            throw (InvalidParameterException)
                    (new InvalidParameterException().initCause(iape));
        }
        if ((mechanism == CKM_DES2_KEY_GEN) ||
            (mechanism == CKM_DES3_KEY_GEN))  {
            long newMechanism = (newSignificantKeySize == 112 ?
                CKM_DES2_KEY_GEN : CKM_DES3_KEY_GEN);
            if (mechanism != newMechanism) {
                if (supportBothKeySizes) {
                    mechanism = newMechanism;
                    // Adjust keyType to reflect the mechanism change
                    keyType = (mechanism == CKM_DES2_KEY_GEN ?
                        CKK_DES2 : CKK_DES3);
                } else {
                    throw new InvalidParameterException
                            ("Only " + significantKeySize +
                             "-bit DESede is supported");
                }
            }
        }
        this.keySize = keySize;
        this.significantKeySize = newSignificantKeySize;
    }

    // see JCE spec
    protected SecretKey engineGenerateKey() {
        Session session = null;
        try {
            session = token.getObjSession();
            CK_ATTRIBUTE[] attributes;
            switch ((int)keyType) {
            case (int)CKK_DES:
            case (int)CKK_DES2:
            case (int)CKK_DES3:
                // fixed length, do not specify CKA_VALUE_LEN
                attributes = new CK_ATTRIBUTE[] {
                    new CK_ATTRIBUTE(CKA_CLASS, CKO_SECRET_KEY),
                };
                break;
            default:
                attributes = new CK_ATTRIBUTE[] {
                    new CK_ATTRIBUTE(CKA_CLASS, CKO_SECRET_KEY),
                    new CK_ATTRIBUTE(CKA_VALUE_LEN, keySize >> 3),
                };
                break;
            }
            attributes = token.getAttributes
                (O_GENERATE, CKO_SECRET_KEY, keyType, attributes);
            long keyID = token.p11.C_GenerateKey
                (session.id(), new CK_MECHANISM(mechanism), attributes);
            return P11Key.secretKey
                (session, keyID, algorithm, significantKeySize, attributes);
        } catch (PKCS11Exception e) {
            throw new ProviderException("Could not generate key", e);
        } finally {
            token.releaseSession(session);
        }
    }

}
