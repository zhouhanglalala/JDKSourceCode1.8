/*
 * Copyright (c) 2000, 2006, Oracle and/or its affiliates. All rights reserved.
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

package sun.security.jgss.krb5;

import org.ietf.jgss.*;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;

class MicToken extends MessageToken {

  public MicToken(Krb5Context context,
                  byte[] tokenBytes, int tokenOffset, int tokenLen,
                  MessageProp prop)  throws GSSException {
        super(Krb5Token.MIC_ID, context,
          tokenBytes, tokenOffset, tokenLen, prop);
  }

  public MicToken(Krb5Context context,
                   InputStream is, MessageProp prop)
    throws GSSException {
    super(Krb5Token.MIC_ID, context, is, prop);
  }

  public void verify(byte[] data, int offset, int len) throws GSSException {
        if (!verifySignAndSeqNumber(null, data, offset, len, null))
      throw new GSSException(GSSException.BAD_MIC, -1,
                         "Corrupt checksum or sequence number in MIC token");
  }

  public void verify(InputStream data) throws GSSException {
    byte[] dataBytes = null;
    try {
      dataBytes = new byte[data.available()];
      data.read(dataBytes);
    } catch (IOException e) {
      // Error reading application data
      throw new GSSException(GSSException.BAD_MIC, -1,
                         "Corrupt checksum or sequence number in MIC token");
    }
      verify(dataBytes, 0, dataBytes.length);
  }

  public MicToken(Krb5Context context, MessageProp prop,
                  byte[] data, int pos, int len)
        throws GSSException {
        super(Krb5Token.MIC_ID, context);

        //      debug("Application data to MicToken verify is [" +
        //            getHexBytes(data, pos, len) + "]\n");
        if (prop == null) prop = new MessageProp(0, false);
        genSignAndSeqNumber(prop, null, data, pos, len, null);
  }

  public MicToken(Krb5Context context, MessageProp prop,
                  InputStream data)
        throws GSSException, IOException {
        super(Krb5Token.MIC_ID, context);
        byte[] dataBytes = new byte[data.available()];
        data.read(dataBytes);

        //debug("Application data to MicToken cons is [" +
        //     getHexBytes(dataBytes) + "]\n");
        if (prop == null) prop = new MessageProp(0, false);
        genSignAndSeqNumber(prop, null, dataBytes, 0, dataBytes.length, null);
  }

  protected int getSealAlg(boolean confRequested, int qop) {
        return (SEAL_ALG_NONE);
  }

  public int encode(byte[] outToken, int offset)
      throws IOException, GSSException {
      // Token  is small
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      super.encode(bos);
      byte[] token = bos.toByteArray();
      System.arraycopy(token, 0, outToken, offset, token.length);
      return token.length;
  }

  public byte[] encode() throws IOException, GSSException{
    // XXX Fine tune this initial size
    ByteArrayOutputStream bos = new ByteArrayOutputStream(50);
    encode(bos);
    return bos.toByteArray();
  }

}
