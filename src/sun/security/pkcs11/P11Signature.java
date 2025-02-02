/*
 * Copyright (c) 2003, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import java.security.*;
import java.security.interfaces.*;
import java.security.spec.AlgorithmParameterSpec;
import sun.nio.ch.DirectBuffer;

import sun.security.util.*;
import sun.security.x509.AlgorithmId;

import sun.security.rsa.RSASignature;
import sun.security.rsa.RSAPadding;

import sun.security.pkcs11.wrapper.*;

import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_DSA;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_DSA_SHA1;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_ECDSA;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_ECDSA_SHA1;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_MD2_RSA_PKCS;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_MD5_RSA_PKCS;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_RSA_PKCS;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_RSA_X_509;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_SHA1_RSA_PKCS;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_SHA224_RSA_PKCS;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_SHA256_RSA_PKCS;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_SHA384_RSA_PKCS;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKM_SHA512_RSA_PKCS;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKR_DATA_LEN_RANGE;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKR_SIGNATURE_INVALID;
import static sun.security.pkcs11.wrapper.PKCS11Constants.CKR_SIGNATURE_LEN_RANGE;

import sun.security.util.KeyUtil;

/**
 * Signature implementation class. This class currently supports the
 * following algorithms:
 *
 * . DSA
 *   . NONEwithDSA (RawDSA)
 *   . SHA1withDSA
 * . RSA:
 *   . MD2withRSA
 *   . MD5withRSA
 *   . SHA1withRSA
 *   . SHA224withRSA
 *   . SHA256withRSA
 *   . SHA384withRSA
 *   . SHA512withRSA
 * . ECDSA
 *   . NONEwithECDSA
 *   . SHA1withECDSA
 *   . SHA224withECDSA
 *   . SHA256withECDSA
 *   . SHA384withECDSA
 *   . SHA512withECDSA
 *
 * Note that the underlying PKCS#11 token may support complete signature
 * algorithm (e.g. CKM_DSA_SHA1, CKM_MD5_RSA_PKCS), or it may just
 * implement the signature algorithm without hashing (e.g. CKM_DSA, CKM_PKCS),
 * or it may only implement the raw public key operation (CKM_RSA_X_509).
 * This class uses what is available and adds whatever extra processing
 * is needed.
 *
 * @author  Andreas Sterbenz
 * @since   1.5
 */
final class P11Signature extends SignatureSpi {

    // token instance
    private final Token token;

    // algorithm name
    private final String algorithm;

    // name of the key algorithm, currently either RSA or DSA
    private final String keyAlgorithm;

    // mechanism id
    private final long mechanism;

    // digest algorithm OID, if we encode RSA signature ourselves
    private final ObjectIdentifier digestOID;

    // type, one of T_* below
    private final int type;

    // key instance used, if init*() was called
    private P11Key p11Key;

    // message digest, if we do the digesting ourselves
    private final MessageDigest md;

    // associated session, if any
    private Session session;

    // mode, one of M_* below
    private int mode;

    // flag indicating whether an operation is initialized
    private boolean initialized;

    // buffer, for update(byte) or DSA
    private final byte[] buffer;

    // total number of bytes processed in current operation
    private int bytesProcessed;

    // constant for signing mode
    private final static int M_SIGN   = 1;
    // constant for verification mode
    private final static int M_VERIFY = 2;

    // constant for type digesting, we do the hashing ourselves
    private final static int T_DIGEST = 1;
    // constant for type update, token does everything
    private final static int T_UPDATE = 2;
    // constant for type raw, used with RawDSA and NONEwithECDSA only
    private final static int T_RAW    = 3;

    // XXX PKCS#11 v2.20 says "should not be longer than 1024 bits",
    // but this is a little arbitrary
    private final static int RAW_ECDSA_MAX = 128;

    P11Signature(Token token, String algorithm, long mechanism)
            throws NoSuchAlgorithmException, PKCS11Exception {
        super();
        this.token = token;
        this.algorithm = algorithm;
        this.mechanism = mechanism;
        byte[] buffer = null;
        ObjectIdentifier digestOID = null;
        MessageDigest md = null;
        switch ((int)mechanism) {
        case (int)CKM_MD2_RSA_PKCS:
        case (int)CKM_MD5_RSA_PKCS:
        case (int)CKM_SHA1_RSA_PKCS:
        case (int)CKM_SHA224_RSA_PKCS:
        case (int)CKM_SHA256_RSA_PKCS:
        case (int)CKM_SHA384_RSA_PKCS:
        case (int)CKM_SHA512_RSA_PKCS:
            keyAlgorithm = "RSA";
            type = T_UPDATE;
            buffer = new byte[1];
            break;
        case (int)CKM_DSA_SHA1:
            keyAlgorithm = "DSA";
            type = T_UPDATE;
            buffer = new byte[1];
            break;
        case (int)CKM_ECDSA_SHA1:
            keyAlgorithm = "EC";
            type = T_UPDATE;
            buffer = new byte[1];
            break;
        case (int)CKM_DSA:
            keyAlgorithm = "DSA";
            if (algorithm.equals("DSA")) {
                type = T_DIGEST;
                md = MessageDigest.getInstance("SHA-1");
            } else if (algorithm.equals("RawDSA")) {
                type = T_RAW;
                buffer = new byte[20];
            } else {
                throw new ProviderException(algorithm);
            }
            break;
        case (int)CKM_ECDSA:
            keyAlgorithm = "EC";
            if (algorithm.equals("NONEwithECDSA")) {
                type = T_RAW;
                buffer = new byte[RAW_ECDSA_MAX];
            } else {
                String digestAlg;
                if (algorithm.equals("SHA1withECDSA")) {
                    digestAlg = "SHA-1";
                } else if (algorithm.equals("SHA224withECDSA")) {
                    digestAlg = "SHA-224";
                } else if (algorithm.equals("SHA256withECDSA")) {
                    digestAlg = "SHA-256";
                } else if (algorithm.equals("SHA384withECDSA")) {
                    digestAlg = "SHA-384";
                } else if (algorithm.equals("SHA512withECDSA")) {
                    digestAlg = "SHA-512";
                } else {
                    throw new ProviderException(algorithm);
                }
                type = T_DIGEST;
                md = MessageDigest.getInstance(digestAlg);
            }
            break;
        case (int)CKM_RSA_PKCS:
        case (int)CKM_RSA_X_509:
            keyAlgorithm = "RSA";
            type = T_DIGEST;
            if (algorithm.equals("MD5withRSA")) {
                md = MessageDigest.getInstance("MD5");
                digestOID = AlgorithmId.MD5_oid;
            } else if (algorithm.equals("SHA1withRSA")) {
                md = MessageDigest.getInstance("SHA-1");
                digestOID = AlgorithmId.SHA_oid;
            } else if (algorithm.equals("MD2withRSA")) {
                md = MessageDigest.getInstance("MD2");
                digestOID = AlgorithmId.MD2_oid;
            } else if (algorithm.equals("SHA224withRSA")) {
                md = MessageDigest.getInstance("SHA-224");
                digestOID = AlgorithmId.SHA224_oid;
            } else if (algorithm.equals("SHA256withRSA")) {
                md = MessageDigest.getInstance("SHA-256");
                digestOID = AlgorithmId.SHA256_oid;
            } else if (algorithm.equals("SHA384withRSA")) {
                md = MessageDigest.getInstance("SHA-384");
                digestOID = AlgorithmId.SHA384_oid;
            } else if (algorithm.equals("SHA512withRSA")) {
                md = MessageDigest.getInstance("SHA-512");
                digestOID = AlgorithmId.SHA512_oid;
            } else {
                throw new ProviderException("Unknown signature: " + algorithm);
            }
            break;
        default:
            throw new ProviderException("Unknown mechanism: " + mechanism);
        }
        this.buffer = buffer;
        this.digestOID = digestOID;
        this.md = md;
    }

    private void ensureInitialized() {
        token.ensureValid();
        if (initialized == false) {
            initialize();
        }
    }

    private void cancelOperation() {
        token.ensureValid();
        if (initialized == false) {
            return;
        }
        initialized = false;
        if ((session == null) || (token.explicitCancel == false)) {
            return;
        }
        if (session.hasObjects() == false) {
            session = token.killSession(session);
            return;
        }
        // "cancel" operation by finishing it
        // XXX make sure all this always works correctly
        if (mode == M_SIGN) {
            try {
                if (type == T_UPDATE) {
                    token.p11.C_SignFinal(session.id(), 0);
                } else {
                    byte[] digest;
                    if (type == T_DIGEST) {
                        digest = md.digest();
                    } else { // T_RAW
                        digest = buffer;
                    }
                    token.p11.C_Sign(session.id(), digest);
                }
            } catch (PKCS11Exception e) {
                throw new ProviderException("cancel failed", e);
            }
        } else { // M_VERIFY
            try {
                byte[] signature;
                if (keyAlgorithm.equals("DSA")) {
                    signature = new byte[40];
                } else {
                    signature = new byte[(p11Key.length() + 7) >> 3];
                }
                if (type == T_UPDATE) {
                    token.p11.C_VerifyFinal(session.id(), signature);
                } else {
                    byte[] digest;
                    if (type == T_DIGEST) {
                        digest = md.digest();
                    } else { // T_RAW
                        digest = buffer;
                    }
                    token.p11.C_Verify(session.id(), digest, signature);
                }
            } catch (PKCS11Exception e) {
                // will fail since the signature is incorrect
                // XXX check error code
            }
        }
    }

    // assumes current state is initialized == false
    private void initialize() {
        try {
            if (session == null) {
                session = token.getOpSession();
            }
            if (mode == M_SIGN) {
                token.p11.C_SignInit(session.id(),
                        new CK_MECHANISM(mechanism), p11Key.keyID);
            } else {
                token.p11.C_VerifyInit(session.id(),
                        new CK_MECHANISM(mechanism), p11Key.keyID);
            }
            initialized = true;
        } catch (PKCS11Exception e) {
            throw new ProviderException("Initialization failed", e);
        }
        if (bytesProcessed != 0) {
            bytesProcessed = 0;
            if (md != null) {
                md.reset();
            }
        }
    }

    private void checkKeySize(String keyAlgo, Key key)
        throws InvalidKeyException {
        CK_MECHANISM_INFO mechInfo = null;
        try {
            mechInfo = token.getMechanismInfo(mechanism);
        } catch (PKCS11Exception e) {
            // should not happen, ignore for now.
        }
        if (mechInfo == null) {
            // skip the check if no native info available
            return;
        }
        int minKeySize = (int) mechInfo.ulMinKeySize;
        int maxKeySize = (int) mechInfo.ulMaxKeySize;
        // need to override the MAX keysize for SHA1withDSA
        if (md != null && mechanism == CKM_DSA && maxKeySize > 1024) {
               maxKeySize = 1024;
        }
        int keySize = 0;
        if (key instanceof P11Key) {
            keySize = ((P11Key) key).length();
        } else {
            if (keyAlgo.equals("RSA")) {
                keySize = ((RSAKey) key).getModulus().bitLength();
            } else if (keyAlgo.equals("DSA")) {
                keySize = ((DSAKey) key).getParams().getP().bitLength();
            } else if (keyAlgo.equals("EC")) {
                keySize = ((ECKey) key).getParams().getCurve().getField().getFieldSize();
            } else {
                throw new ProviderException("Error: unsupported algo " + keyAlgo);
            }
        }
        if ((minKeySize != -1) && (keySize < minKeySize)) {
            throw new InvalidKeyException(keyAlgo +
                " key must be at least " + minKeySize + " bits");
        }
        if ((maxKeySize != -1) && (keySize > maxKeySize)) {
            throw new InvalidKeyException(keyAlgo +
                " key must be at most " + maxKeySize + " bits");
        }
        if (keyAlgo.equals("RSA")) {
            checkRSAKeyLength(keySize);
        }
    }

    private void checkRSAKeyLength(int len) throws InvalidKeyException {
        RSAPadding padding;
        try {
            padding = RSAPadding.getInstance
                (RSAPadding.PAD_BLOCKTYPE_1, (len + 7) >> 3);
        } catch (InvalidAlgorithmParameterException iape) {
            throw new InvalidKeyException(iape.getMessage());
        }
        int maxDataSize = padding.getMaxDataSize();
        int encodedLength;
        if (algorithm.equals("MD5withRSA") ||
            algorithm.equals("MD2withRSA")) {
            encodedLength = 34;
        } else if (algorithm.equals("SHA1withRSA")) {
            encodedLength = 35;
        } else if (algorithm.equals("SHA224withRSA")) {
            encodedLength = 47;
        } else if (algorithm.equals("SHA256withRSA")) {
            encodedLength = 51;
        } else if (algorithm.equals("SHA384withRSA")) {
            encodedLength = 67;
        } else if (algorithm.equals("SHA512withRSA")) {
            encodedLength = 83;
        } else {
            throw new ProviderException("Unknown signature algo: " + algorithm);
        }
        if (encodedLength > maxDataSize) {
            throw new InvalidKeyException
                ("Key is too short for this signature algorithm");
        }
    }

    // see JCA spec
    @Override
    protected void engineInitVerify(PublicKey publicKey)
            throws InvalidKeyException {
        if (publicKey == null) {
            throw new InvalidKeyException("Key must not be null");
        }
        // Need to check key length whenever a new key is set
        if (publicKey != p11Key) {
            checkKeySize(keyAlgorithm, publicKey);
        }
        cancelOperation();
        mode = M_VERIFY;
        p11Key = P11KeyFactory.convertKey(token, publicKey, keyAlgorithm);
        initialize();
    }

    // see JCA spec
    @Override
    protected void engineInitSign(PrivateKey privateKey)
            throws InvalidKeyException {
        if (privateKey == null) {
            throw new InvalidKeyException("Key must not be null");
        }
        // Need to check RSA key length whenever a new key is set
        if (privateKey != p11Key) {
            checkKeySize(keyAlgorithm, privateKey);
        }
        cancelOperation();
        mode = M_SIGN;
        p11Key = P11KeyFactory.convertKey(token, privateKey, keyAlgorithm);
        initialize();
    }

    // see JCA spec
    @Override
    protected void engineUpdate(byte b) throws SignatureException {
        ensureInitialized();
        switch (type) {
        case T_UPDATE:
            buffer[0] = b;
            engineUpdate(buffer, 0, 1);
            break;
        case T_DIGEST:
            md.update(b);
            bytesProcessed++;
            break;
        case T_RAW:
            if (bytesProcessed >= buffer.length) {
                bytesProcessed = buffer.length + 1;
                return;
            }
            buffer[bytesProcessed++] = b;
            break;
        default:
            throw new ProviderException("Internal error");
        }
    }

    // see JCA spec
    @Override
    protected void engineUpdate(byte[] b, int ofs, int len)
            throws SignatureException {
        ensureInitialized();
        if (len == 0) {
            return;
        }
        switch (type) {
        case T_UPDATE:
            try {
                if (mode == M_SIGN) {
                    token.p11.C_SignUpdate(session.id(), 0, b, ofs, len);
                } else {
                    token.p11.C_VerifyUpdate(session.id(), 0, b, ofs, len);
                }
                bytesProcessed += len;
            } catch (PKCS11Exception e) {
                throw new ProviderException(e);
            }
            break;
        case T_DIGEST:
            md.update(b, ofs, len);
            bytesProcessed += len;
            break;
        case T_RAW:
            if (bytesProcessed + len > buffer.length) {
                bytesProcessed = buffer.length + 1;
                return;
            }
            System.arraycopy(b, ofs, buffer, bytesProcessed, len);
            bytesProcessed += len;
            break;
        default:
            throw new ProviderException("Internal error");
        }
    }

    // see JCA spec
    @Override
    protected void engineUpdate(ByteBuffer byteBuffer) {
        ensureInitialized();
        int len = byteBuffer.remaining();
        if (len <= 0) {
            return;
        }
        switch (type) {
        case T_UPDATE:
            if (byteBuffer instanceof DirectBuffer == false) {
                // cannot do better than default impl
                super.engineUpdate(byteBuffer);
                return;
            }
            long addr = ((DirectBuffer)byteBuffer).address();
            int ofs = byteBuffer.position();
            try {
                if (mode == M_SIGN) {
                    token.p11.C_SignUpdate
                        (session.id(), addr + ofs, null, 0, len);
                } else {
                    token.p11.C_VerifyUpdate
                        (session.id(), addr + ofs, null, 0, len);
                }
                bytesProcessed += len;
                byteBuffer.position(ofs + len);
            } catch (PKCS11Exception e) {
                throw new ProviderException("Update failed", e);
            }
            break;
        case T_DIGEST:
            md.update(byteBuffer);
            bytesProcessed += len;
            break;
        case T_RAW:
            if (bytesProcessed + len > buffer.length) {
                bytesProcessed = buffer.length + 1;
                return;
            }
            byteBuffer.get(buffer, bytesProcessed, len);
            bytesProcessed += len;
            break;
        default:
            throw new ProviderException("Internal error");
        }
    }

    // see JCA spec
    @Override
    protected byte[] engineSign() throws SignatureException {
        ensureInitialized();
        try {
            byte[] signature;
            if (type == T_UPDATE) {
                int len = keyAlgorithm.equals("DSA") ? 40 : 0;
                signature = token.p11.C_SignFinal(session.id(), len);
            } else {
                byte[] digest;
                if (type == T_DIGEST) {
                    digest = md.digest();
                } else { // T_RAW
                    if (mechanism == CKM_DSA) {
                        if (bytesProcessed != buffer.length) {
                            throw new SignatureException
                            ("Data for RawDSA must be exactly 20 bytes long");
                        }
                        digest = buffer;
                    } else { // CKM_ECDSA
                        if (bytesProcessed > buffer.length) {
                            throw new SignatureException("Data for NONEwithECDSA"
                            + " must be at most " + RAW_ECDSA_MAX + " bytes long");
                        }
                        digest = new byte[bytesProcessed];
                        System.arraycopy(buffer, 0, digest, 0, bytesProcessed);
                    }
                }
                if (keyAlgorithm.equals("RSA") == false) {
                    // DSA and ECDSA
                    signature = token.p11.C_Sign(session.id(), digest);
                } else { // RSA
                    byte[] data = encodeSignature(digest);
                    if (mechanism == CKM_RSA_X_509) {
                        data = pkcs1Pad(data);
                    }
                    signature = token.p11.C_Sign(session.id(), data);
                }
            }
            if (keyAlgorithm.equals("RSA") == false) {
                return dsaToASN1(signature);
            } else {
                return signature;
            }
        } catch (PKCS11Exception e) {
            throw new ProviderException(e);
        } finally {
            initialized = false;
            session = token.releaseSession(session);
        }
    }

    // see JCA spec
    @Override
    protected boolean engineVerify(byte[] signature) throws SignatureException {
        ensureInitialized();
        try {
            if (keyAlgorithm.equals("DSA")) {
                signature = asn1ToDSA(signature);
            } else if (keyAlgorithm.equals("EC")) {
                signature = asn1ToECDSA(signature);
            }
            if (type == T_UPDATE) {
                token.p11.C_VerifyFinal(session.id(), signature);
            } else {
                byte[] digest;
                if (type == T_DIGEST) {
                    digest = md.digest();
                } else { // T_RAW
                    if (mechanism == CKM_DSA) {
                        if (bytesProcessed != buffer.length) {
                            throw new SignatureException
                            ("Data for RawDSA must be exactly 20 bytes long");
                        }
                        digest = buffer;
                    } else {
                        if (bytesProcessed > buffer.length) {
                            throw new SignatureException("Data for NONEwithECDSA"
                            + " must be at most " + RAW_ECDSA_MAX + " bytes long");
                        }
                        digest = new byte[bytesProcessed];
                        System.arraycopy(buffer, 0, digest, 0, bytesProcessed);
                    }
                }
                if (keyAlgorithm.equals("RSA") == false) {
                    // DSA and ECDSA
                    token.p11.C_Verify(session.id(), digest, signature);
                } else { // RSA
                    byte[] data = encodeSignature(digest);
                    if (mechanism == CKM_RSA_X_509) {
                        data = pkcs1Pad(data);
                    }
                    token.p11.C_Verify(session.id(), data, signature);
                }
            }
            return true;
        } catch (PKCS11Exception e) {
            long errorCode = e.getErrorCode();
            if (errorCode == CKR_SIGNATURE_INVALID) {
                return false;
            }
            if (errorCode == CKR_SIGNATURE_LEN_RANGE) {
                // return false rather than throwing an exception
                return false;
            }
            // ECF bug?
            if (errorCode == CKR_DATA_LEN_RANGE) {
                return false;
            }
            throw new ProviderException(e);
        } finally {
            // XXX we should not release the session if we abort above
            // before calling C_Verify
            initialized = false;
            session = token.releaseSession(session);
        }
    }

    private byte[] pkcs1Pad(byte[] data) {
        try {
            int len = (p11Key.length() + 7) >> 3;
            RSAPadding padding = RSAPadding.getInstance
                                        (RSAPadding.PAD_BLOCKTYPE_1, len);
            byte[] padded = padding.pad(data);
            return padded;
        } catch (GeneralSecurityException e) {
            throw new ProviderException(e);
        }
    }

    private byte[] encodeSignature(byte[] digest) throws SignatureException {
        try {
            return RSASignature.encodeSignature(digestOID, digest);
        } catch (IOException e) {
            throw new SignatureException("Invalid encoding", e);
        }
    }

//    private static byte[] decodeSignature(byte[] signature) throws IOException {
//      return RSASignature.decodeSignature(digestOID, signature);
//    }

    // For DSA and ECDSA signatures, PKCS#11 represents them as a simple
    // byte array that contains the concatenation of r and s.
    // For DSA, r and s are always exactly 20 bytes long.
    // For ECDSA, r and s are of variable length, but we know that each
    // occupies half of the array.
    private static byte[] dsaToASN1(byte[] signature) {
        int n = signature.length >> 1;
        BigInteger r = new BigInteger(1, P11Util.subarray(signature, 0, n));
        BigInteger s = new BigInteger(1, P11Util.subarray(signature, n, n));
        try {
            DerOutputStream outseq = new DerOutputStream(100);
            outseq.putInteger(r);
            outseq.putInteger(s);
            DerValue result = new DerValue(DerValue.tag_Sequence,
                                           outseq.toByteArray());
            return result.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Internal error", e);
        }
    }

    private static byte[] asn1ToDSA(byte[] signature) throws SignatureException {
        try {
            DerInputStream in = new DerInputStream(signature);
            DerValue[] values = in.getSequence(2);
            BigInteger r = values[0].getPositiveBigInteger();
            BigInteger s = values[1].getPositiveBigInteger();
            byte[] br = toByteArray(r, 20);
            byte[] bs = toByteArray(s, 20);
            if ((br == null) || (bs == null)) {
                throw new SignatureException("Out of range value for R or S");
            }
            return P11Util.concat(br, bs);
        } catch (SignatureException e) {
            throw e;
        } catch (Exception e) {
            throw new SignatureException("invalid encoding for signature", e);
        }
    }

    private byte[] asn1ToECDSA(byte[] signature) throws SignatureException {
        try {
            DerInputStream in = new DerInputStream(signature);
            DerValue[] values = in.getSequence(2);
            BigInteger r = values[0].getPositiveBigInteger();
            BigInteger s = values[1].getPositiveBigInteger();
            // trim leading zeroes
            byte[] br = KeyUtil.trimZeroes(r.toByteArray());
            byte[] bs = KeyUtil.trimZeroes(s.toByteArray());
            int k = Math.max(br.length, bs.length);
            // r and s each occupy half the array
            byte[] res = new byte[k << 1];
            System.arraycopy(br, 0, res, k - br.length, br.length);
            System.arraycopy(bs, 0, res, res.length - bs.length, bs.length);
            return res;
        } catch (Exception e) {
            throw new SignatureException("invalid encoding for signature", e);
        }
    }

    private static byte[] toByteArray(BigInteger bi, int len) {
        byte[] b = bi.toByteArray();
        int n = b.length;
        if (n == len) {
            return b;
        }
        if ((n == len + 1) && (b[0] == 0)) {
            byte[] t = new byte[len];
            System.arraycopy(b, 1, t, 0, len);
            return t;
        }
        if (n > len) {
            return null;
        }
        // must be smaller
        byte[] t = new byte[len];
        System.arraycopy(b, 0, t, (len - n), n);
        return t;
    }

    // see JCA spec
    @Override
    protected void engineSetParameter(String param, Object value)
            throws InvalidParameterException {
        throw new UnsupportedOperationException("setParameter() not supported");
    }

    // see JCA spec
    @Override
    protected void engineSetParameter(AlgorithmParameterSpec params)
            throws InvalidAlgorithmParameterException {
        if (params != null) {
            throw new InvalidAlgorithmParameterException("No parameter accepted");
        }
    }

    // see JCA spec
    @Override
    protected Object engineGetParameter(String param)
            throws InvalidParameterException {
        throw new UnsupportedOperationException("getParameter() not supported");
    }

    // see JCA spec
    @Override
    protected AlgorithmParameters engineGetParameters() {
        return null;
    }
}
