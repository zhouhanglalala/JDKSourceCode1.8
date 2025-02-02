/*
 * Copyright (c) 2003, 2006, Oracle and/or its affiliates. All rights reserved.
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

/*
 */

package sun.nio.cs.ext;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

import sun.nio.cs.Surrogate;


public abstract class SimpleEUCEncoder
    extends CharsetEncoder
{

    protected short  index1[];
    protected String index2;
    protected String index2a;
    protected String index2b;
    protected String index2c;
    protected int    mask1;
    protected int    mask2;
    protected int    shift;

    private byte[] outputByte = new byte[4];
    private final Surrogate.Parser sgp = new Surrogate.Parser();

    protected SimpleEUCEncoder(Charset cs)
    {
        super(cs, 3.0f, 4.0f);
    }

    /**
     * Returns true if the given character can be converted to the
     * target character encoding.
     */

    public boolean canEncode(char ch) {
       int    index;
       String theChars;

       index = index1[((ch & mask1) >> shift)] + (ch & mask2);

       if (index < 7500)
         theChars = index2;
       else
         if (index < 15000) {
           index = index - 7500;
           theChars = index2a;
         }
         else
           if (index < 22500){
             index = index - 15000;
             theChars = index2b;
           }
           else {
             index = index - 22500;
             theChars = index2c;
           }

       if (theChars.charAt(2*index) != '\u0000' ||
                    theChars.charAt(2*index + 1) != '\u0000')
         return (true);

       // only return true if input char was unicode null - all others are
       //     undefined
       return( ch == '\u0000');

    }
    private CoderResult encodeArrayLoop(CharBuffer src, ByteBuffer dst) {
        char[] sa = src.array();
        int sp = src.arrayOffset() + src.position();
        int sl = src.arrayOffset() + src.limit();
        assert (sp <= sl);
        sp = (sp <= sl ? sp : sl);
        byte[] da = dst.array();
        int dp = dst.arrayOffset() + dst.position();
        int dl = dst.arrayOffset() + dst.limit();
        assert (dp <= dl);
        dp = (dp <= dl ? dp : dl);

        int     index;
        int     spaceNeeded;
        int     i;

        try {
            while (sp < sl) {
                boolean allZeroes = true;
                char inputChar = sa[sp];
                if (Character.isSurrogate(inputChar)) {
                    if (sgp.parse(inputChar, sa, sp, sl) < 0)
                        return sgp.error();
                    return sgp.unmappableResult();
                }

                if (inputChar >= '\uFFFE')
                    return CoderResult.unmappableForLength(1);

                String theChars;
                char   aChar;

                 // We have a valid character, get the bytes for it
                index = index1[((inputChar & mask1) >> shift)] + (inputChar & mask2);

                if (index < 7500)
                    theChars = index2;
                else if (index < 15000) {
                     index = index - 7500;
                     theChars = index2a;
                } else if (index < 22500){
                    index = index - 15000;
                    theChars = index2b;
                }
                else {
                    index = index - 22500;
                    theChars = index2c;
                }

                aChar = theChars.charAt(2*index);
                outputByte[0] = (byte)((aChar & 0xff00)>>8);
                outputByte[1] = (byte)(aChar & 0x00ff);
                aChar = theChars.charAt(2*index + 1);
                outputByte[2] = (byte)((aChar & 0xff00)>>8);
                outputByte[3] = (byte)(aChar & 0x00ff);

            for (i = 0; i < outputByte.length; i++) {
                if (outputByte[i] != 0x00) {
                allZeroes = false;
                break;
                }
            }

            if (allZeroes && inputChar != '\u0000') {
                return CoderResult.unmappableForLength(1);
            }

            int oindex = 0;

            for (spaceNeeded = outputByte.length;
                 spaceNeeded > 1; spaceNeeded--){
                if (outputByte[oindex++] != 0x00 )
                    break;
            }

            if (dp + spaceNeeded > dl)
                return CoderResult.OVERFLOW;

            for (i = outputByte.length - spaceNeeded;
                 i < outputByte.length; i++) {
                    da[dp++] = outputByte[i];
            }
            sp++;
        }
        return CoderResult.UNDERFLOW;
        } finally {
            src.position(sp - src.arrayOffset());
            dst.position(dp - dst.arrayOffset());
        }
    }

    private CoderResult encodeBufferLoop(CharBuffer src, ByteBuffer dst) {
        int     index;
        int     spaceNeeded;
        int     i;
        int mark = src.position();
        try {
            while (src.hasRemaining()) {
                char inputChar = src.get();
                boolean allZeroes = true;
                if (Character.isSurrogate(inputChar)) {
                    if (sgp.parse(inputChar, src) < 0)
                        return sgp.error();
                    return sgp.unmappableResult();
                }

                if (inputChar >= '\uFFFE')
                    return CoderResult.unmappableForLength(1);

                String theChars;
                char   aChar;

                 // We have a valid character, get the bytes for it
                index = index1[((inputChar & mask1) >> shift)] + (inputChar & mask2);

                if (index < 7500)
                    theChars = index2;
                else if (index < 15000) {
                     index = index - 7500;
                     theChars = index2a;
                } else if (index < 22500){
                    index = index - 15000;
                    theChars = index2b;
                }
                else {
                    index = index - 22500;
                    theChars = index2c;
                }

                aChar = theChars.charAt(2*index);
                outputByte[0] = (byte)((aChar & 0xff00)>>8);
                outputByte[1] = (byte)(aChar & 0x00ff);
                aChar = theChars.charAt(2*index + 1);
                outputByte[2] = (byte)((aChar & 0xff00)>>8);
                outputByte[3] = (byte)(aChar & 0x00ff);

            for (i = 0; i < outputByte.length; i++) {
                if (outputByte[i] != 0x00) {
                allZeroes = false;
                break;
                }
            }
            if (allZeroes && inputChar != '\u0000') {
                return CoderResult.unmappableForLength(1);
            }

            int oindex = 0;

            for (spaceNeeded = outputByte.length;
                 spaceNeeded > 1; spaceNeeded--){
                if (outputByte[oindex++] != 0x00 )
                    break;
            }
            if (dst.remaining() < spaceNeeded)
                return CoderResult.OVERFLOW;

            for (i = outputByte.length - spaceNeeded;
                 i < outputByte.length; i++) {
                    dst.put(outputByte[i]);
            }
            mark++;
            }
            return CoderResult.UNDERFLOW;
        } finally {
            src.position(mark);
        }
    }

    protected CoderResult encodeLoop(CharBuffer src, ByteBuffer dst) {
        if (true && src.hasArray() && dst.hasArray())
            return encodeArrayLoop(src, dst);
        else
            return encodeBufferLoop(src, dst);
    }

    public byte encode(char inputChar) {
        return (byte)index2.charAt(index1[(inputChar & mask1) >> shift] +
                (inputChar & mask2));
    }
}
