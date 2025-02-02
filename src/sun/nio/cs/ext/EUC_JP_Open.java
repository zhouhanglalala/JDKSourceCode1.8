/*
 * Copyright (c) 2003, 2012, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.cs.ext;

import static sun.nio.cs.CharsetMapping.UNMAPPABLE_DECODING;
import static sun.nio.cs.CharsetMapping.UNMAPPABLE_ENCODING;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

import sun.nio.cs.HistoricallyNamedCharset;

public class EUC_JP_Open
    extends Charset
    implements HistoricallyNamedCharset
{
    public EUC_JP_Open() {
        super("x-eucJP-Open", ExtendedCharsets.aliasesFor("x-eucJP-Open"));
    }

    public String historicalName() {
        return "EUC_JP_Solaris";
    }

    public boolean contains(Charset cs) {
        return ((cs.name().equals("US-ASCII"))
                || (cs instanceof JIS_X_0201)
                || (cs instanceof EUC_JP));
    }

    public CharsetDecoder newDecoder() {
        return new Decoder(this);
    }

    public CharsetEncoder newEncoder() {
        return new Encoder(this);
    }

    private static class Decoder extends EUC_JP.Decoder {
        private static DoubleByte.Decoder DEC0208_Solaris =
            (DoubleByte.Decoder)new JIS_X_0208_Solaris().newDecoder();
        private static DoubleByte.Decoder DEC0212_Solaris =
            (DoubleByte.Decoder)new JIS_X_0212_Solaris().newDecoder();

        private Decoder(Charset cs) {
            // JIS_X_0208_Solaris only has the "extra" mappings, it
            // does not have the JIS_X_0208 entries
            super(cs, 0.5f, 1.0f, DEC0201, DEC0208, DEC0212_Solaris);
        }

        protected char decodeDouble(int byte1, int byte2) {
            char c = super.decodeDouble(byte1, byte2);
            if (c == UNMAPPABLE_DECODING)
                return DEC0208_Solaris.decodeDouble(byte1 - 0x80, byte2 - 0x80);
            return c;
        }
    }

    private static class Encoder extends EUC_JP.Encoder {
        private static DoubleByte.Encoder ENC0208_Solaris =
            (DoubleByte.Encoder)new JIS_X_0208_Solaris().newEncoder();

        private static DoubleByte.Encoder ENC0212_Solaris =
            (DoubleByte.Encoder)new JIS_X_0212_Solaris().newEncoder();

        private Encoder(Charset cs) {
            // The EUC_JP_Open has some interesting tweak for the
            // encoding, so can't just pass the euc0208_solaris to
            // the euc_jp. Have to override the encodeDouble() as
            // showed below (mapping testing catches this).
            // super(cs, 3.0f, 3.0f, ENC0201, ENC0208_Solaris, ENC0212_Solaris);
            super(cs);
        }

        protected int encodeDouble(char ch) {
            int b = super.encodeDouble(ch);
            if (b != UNMAPPABLE_ENCODING)
                return b;
            b = ENC0208_Solaris.encodeChar(ch);
            if (b != UNMAPPABLE_ENCODING && b > 0x7500) {
                return 0x8F8080 + ENC0212_Solaris.encodeChar(ch);
            }
            return b == UNMAPPABLE_ENCODING ? b : b + 0x8080;

        }
    }
}
