/*
 * Copyright (c) 2003, 2004, Oracle and/or its affiliates. All rights reserved.
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

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.Arrays;

import sun.nio.cs.HistoricallyNamedCharset;

public class IBM942C extends Charset implements HistoricallyNamedCharset
{
    public IBM942C() {
        super("x-IBM942C", ExtendedCharsets.aliasesFor("x-IBM942C"));
    }

    public String historicalName() {
        return "Cp942C";
    }

    public boolean contains(Charset cs) {
        return ((cs.name().equals("US-ASCII"))
                || (cs instanceof IBM942C));
    }

    public CharsetDecoder newDecoder() {
        return new DoubleByte.Decoder(this,
                                      IBM942.b2c,
                                      b2cSB,
                                      0x40,
                                      0xfc);
    }

    public CharsetEncoder newEncoder() {
        return new DoubleByte.Encoder(this, c2b, c2bIndex);
    }

    final static char[] b2cSB;
    final static char[] c2b;
    final static char[] c2bIndex;

    static {
        IBM942.initb2c();

        // the mappings need udpate are
        //    u+001a  <-> 0x1a
        //    u+001c  <-> 0x1c
        //    u+005c  <-> 0x5c
        //    u+007e  <-> 0x7e
        //    u+007f  <-> 0x7f

        b2cSB = Arrays.copyOf(IBM942.b2cSB, IBM942.b2cSB.length);
        b2cSB[0x1a] = 0x1a;
        b2cSB[0x1c] = 0x1c;
        b2cSB[0x5c] = 0x5c;
        b2cSB[0x7e] = 0x7e;
        b2cSB[0x7f] = 0x7f;

        IBM942.initc2b();
        c2b = Arrays.copyOf(IBM942.c2b, IBM942.c2b.length);
        c2bIndex = Arrays.copyOf(IBM942.c2bIndex, IBM942.c2bIndex.length);
        c2b[c2bIndex[0] + 0x1a] = 0x1a;
        c2b[c2bIndex[0] + 0x1c] = 0x1c;
        c2b[c2bIndex[0] + 0x5c] = 0x5c;
        c2b[c2bIndex[0] + 0x7e] = 0x7e;
        c2b[c2bIndex[0] + 0x7f] = 0x7f;
    }
}
