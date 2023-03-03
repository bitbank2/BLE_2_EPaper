package com.example.eslimagetransfer;

import java.util.Arrays;

public class G4Encoder {
    //
    // CCITT G4 image encoding library
    // Written by Larry Bank
    // Copyright (c) 2000-2023 BitBank Software, Inc.
    // Java version started February 13, 2023
    // Designed to encode 1-bpp images
    // as highly compressed CCITT G4 data
    //

    private static final int REGISTER_WIDTH = 32;
    public static final int G4ENC_LSB_FIRST = 0;
    public static final int G4ENC_MSB_FIRST = 1;

    // Error codes
    public static final int  G4ENC_SUCCESS = 0;
    public static final int  G4ENC_NOT_INITIALIZED = 1;
    public static final int  G4ENC_INVALID_PARAMETER = 2;
    public static final int  G4ENC_DATA_OVERFLOW = 3;
    public static final int  G4ENC_IMAGE_COMPLETE = 4;

    // Current variable length encoding info
    private static int ulBits; // buffered bits
    private static int ulBitOff; // current bit offset
    private static int iMaxBufSize;
    private static int iWidth, iHeight; // image size
    private static int iError; // last error
    private static int iFillOrder;
    private static int y;
    private static int iOutSize;
    private static byte[] outbuf;
    private static int[] curLine;
    private static int[] refLine; // current and reference lines

    /* Number of consecutive 1 bits in a byte from MSB to LSB */
    private static final byte[] bitcount =
    {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  /* 0-15 */
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  /* 16-31 */
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  /* 32-47 */
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  /* 48-63 */
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  /* 64-79 */
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  /* 80-95 */
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  /* 96-111 */
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  /* 112-127 */
            1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,  /* 128-143 */
            1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,  /* 144-159 */
            1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,  /* 160-175 */
            1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,  /* 176-191 */
            2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,  /* 192-207 */
            2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,  /* 208-223 */
            3,3,3,3,3,3,3,3,3,3,3,3,3,3,3,3,  /* 224-239 */
            4,4,4,4,4,4,4,4,5,5,5,5,6,6,7,8}; /* 240-255 */

    /* Table of vertical codes for G4 encoding */
    /* code followed by length, starting with v(-3) */
    private static final byte[] vtable =
    {3,7,     /* V(-3) = 0000011 */
            3,6,     /* V(-2) = 000011  */
            3,3,     /* V(-1) = 011     */
            1,1,     /* V(0)  = 1       */
            2,3,     /* V(1)  = 010     */
            2,6,     /* V(2)  = 000010  */
            2,7};    /* V(3)  = 0000010 */

    /* Group 3 Huffman codes ordered for MH encoding */
    /* first, the terminating codes for white (code, length) */
    private static final int[] huff_white =
    {0x35,8,7,6,7,4,8,4,0xb,4, /* 0,1,2,3,4 */
            0xc,4,0xe,4,0xf,4,0x13,5,0x14,5,7,5,8,5, /* 5,6,7,8,9,10,11 */
            8,6,3,6,0x34,6,0x35,6,0x2a,6,0x2b,6,0x27,7, /* 12,13,14,15,16,17,18 */
            0xc,7,8,7,0x17,7,3,7,4,7,0x28,7,0x2b,7, /* 19,20,21,22,23,24,25 */
            0x13,7,0x24,7,0x18,7,2,8,3,8,0x1a,8,0x1b,8, /* 26,27,28,29,30,31,32 */
            0x12,8,0x13,8,0x14,8,0x15,8,0x16,8,0x17,8,0x28,8, /* 33,34,35,36,37,38,39 */
            0x29,8,0x2a,8,0x2b,8,0x2c,8,0x2d,8,4,8,5,8, /* 40,41,42,43,44,45,46 */
            0xa,8,0xb,8,0x52,8,0x53,8,0x54,8,0x55,8,0x24,8, /* 47,48,49,50,51,52,53 */
            0x25,8,0x58,8,0x59,8,0x5a,8,0x5b,8,0x4a,8,0x4b,8, /* 54,55,56,57,58,59,60 */
            0x32,8,0x33,8,0x34,8};                        /* 61,62,63 */

    /* now the white make-up codes */
    private static final int[] huff_wmuc =
    {0,0,0x1b,5,0x12,5,0x17,6,0x37,7,0x36,8,   /* null,64,128,192,256,320 */
            0x37,8,0x64,8,0x65,8,0x68,8,0x67,8,0xcc,9, /* 384,448,512,576,640,704 */
            0xcd,9,0xd2,9,0xd3,9,0xd4,9,0xd5,9,    /* 768,832,896,960,1024 */
            0xd6,9,0xd7,9,0xd8,9,0xd9,9,0xda,9,    /* 1088,1152,1216,1280,1344 */
            0xdb,9,0x98,9,0x99,9,0x9a,9,0x18,6,    /* 1408,1472,1536,1600,1664 */
            0x9b,9,8,11,0xc,11,0xd,11,0x12,12,     /* 1728,1792,1856,1920,1984 */
            0x13,12,0x14,12,0x15,12,0x16,12,0x17,12, /* 2048,2112,2176,2240,2304 */
            0x1c,12,0x1d,12,0x1e,12,0x1f,12};       /* 2368,2432,2496,2560 */

    /* black terminating codes */
    private static final int[] huff_black =
    {0x37,10,2,3,3,2,2,2,3,3,                         /* 0,1,2,3,4 */
            3,4,2,4,3,5,5,6,4,6,4,7,5,7,                     /* 5,6,7,8,9,10,11 */
            7,7,4,8,7,8,0x18,9,0x17,10,0x18,10,8,10,         /* 12,13,14,15,16,17,18 */
            0x67,11,0x68,11,0x6c,11,0x37,11,0x28,11,0x17,11, /* 19,20,21,22,23,24 */
            0x18,11,0xca,12,0xcb,12,0xcc,12,0xcd,12,0x68,12, /* 25,26,27,28,29,30 */
            0x69,12,0x6a,12,0x6b,12,0xd2,12,0xd3,12,0xd4,12, /* 31,32,33,34,35,36 */
            0xd5,12,0xd6,12,0xd7,12,0x6c,12,0x6d,12,0xda,12, /* 37,38,39,40,41,42 */
            0xdb,12,0x54,12,0x55,12,0x56,12,0x57,12,0x64,12, /* 43,44,45,46,47,48 */
            0x65,12,0x52,12,0x53,12,0x24,12,0x37,12,0x38,12, /* 49,50,51,52,53,54 */
            0x27,12,0x28,12,0x58,12,0x59,12,0x2b,12,0x2c,12, /* 55,56,57,58,59,60 */
            0x5a,12,0x66,12,0x67,12};                        /* 61,62,63 */
    /* black make up codes */
    private static final int[] huff_bmuc =
    {0,0,0xf,10,0xc8,12,0xc9,12,0x5b,12,0x33,12, /* null,64,128,192,256,320 */
            0x34,12,0x35,12,0x6c,13,0x6d,13,0x4a,13,0x4b,13,   /* 384,448,512,576,640,704 */
            0x4c,13,0x4d,13,0x72,13,0x73,13,0x74,13,0x75,13,   /* 768,832,896,960,1024,1088 */
            0x76,13,0x77,13,0x52,13,0x53,13,0x54,13,0x55,13,   /* 1152,1216,1280,1344,1408,1472 */
            0x5a,13,0x5b,13,0x64,13,0x65,13,8,11,0xc,11,       /* 1536,1600,1664,1728,1792,1856 */
            0xd,11,0x12,12,0x13,12,0x14,12,0x15,12,0x16,12,    /* 1920,1984,2048,2112,2176,2240 */
            0x17,12,0x1c,12,0x1d,12,0x1e,12,0x1f,12};          /* 2304,2368,2432,2496,2560 */

    /* Table of byte flip values to mirror-image incoming CCITT data */
    private static final int[] ucMirror =
    {0, 128, 64, 192, 32, 160, 96, 224, 16, 144, 80, 208, 48, 176, 112, 240,
            8, 136, 72, 200, 40, 168, 104, 232, 24, 152, 88, 216, 56, 184, 120, 248,
            4, 132, 68, 196, 36, 164, 100, 228, 20, 148, 84, 212, 52, 180, 116, 244,
            12, 140, 76, 204, 44, 172, 108, 236, 28, 156, 92, 220, 60, 188, 124, 252,
            2, 130, 66, 194, 34, 162, 98, 226, 18, 146, 82, 210, 50, 178, 114, 242,
            10, 138, 74, 202, 42, 170, 106, 234, 26, 154, 90, 218, 58, 186, 122, 250,
            6, 134, 70, 198, 38, 166, 102, 230, 22, 150, 86, 214, 54, 182, 118, 246,
            14, 142, 78, 206, 46, 174, 110, 238, 30, 158, 94, 222, 62, 190, 126, 254,
            1, 129, 65, 193, 33, 161, 97, 225, 17, 145, 81, 209, 49, 177, 113, 241,
            9, 137, 73, 201, 41, 169, 105, 233, 25, 153, 89, 217, 57, 185, 121, 249,
            5, 133, 69, 197, 37, 165, 101, 229, 21, 149, 85, 213, 53, 181, 117, 245,
            13, 141, 77, 205, 45, 173, 109, 237, 29, 157, 93, 221, 61, 189, 125, 253,
            3, 131, 67, 195, 35, 163, 99, 227, 19, 147, 83, 211, 51, 179, 115, 243,
            11, 139, 75, 203, 43, 171, 107, 235, 27, 155, 91, 219, 59, 187, 123, 251,
            7, 135, 71, 199, 39, 167, 103, 231, 23, 151, 87, 215, 55, 183, 119, 247,
            15, 143, 79, 207, 47, 175, 111, 239, 31, 159, 95, 223, 63, 191, 127, 255};

    private static void G4ENCInsertCode(int ulCode, int iLen)
    {
        if (ulBitOff + iLen > REGISTER_WIDTH) { // need to write data
            ulBits |= (ulCode >> (ulBitOff + iLen - REGISTER_WIDTH)); // partial bits on first word
            outbuf[iOutSize++] = (byte)((ulBits >> 24) & 0xff); // store in big endian order
            outbuf[iOutSize++] = (byte)((ulBits >> 16) & 0xff);
            outbuf[iOutSize++] = (byte)((ulBits >> 8) & 0xff);
            outbuf[iOutSize++] = (byte)(ulBits & 0xff);
            ulBits = ulCode << ((REGISTER_WIDTH*2) - (ulBitOff + iLen));
            ulBitOff += iLen - REGISTER_WIDTH;
        } else {
            ulBits |= (ulCode << (REGISTER_WIDTH - ulBitOff - iLen));
            ulBitOff += iLen;
        }
    } /* G4ENCInsertCode() */

    //
    // Flush any buffered bits to the output
    //
    private static void G4ENCFlushBits()
    {
        while (ulBitOff >= 8) {
            outbuf[iOutSize++] = (byte)(ulBits >> (REGISTER_WIDTH - 8));
            ulBits <<= 8;
            ulBitOff -= 8;
        }
        outbuf[iOutSize++] = (byte) (ulBits >> (REGISTER_WIDTH - 8));
        ulBitOff = 0;
        ulBits = 0;
    } /* G4ENCFlushBits() */
    //
    // Internal function to add a WHITE pixel run
    //
    private static void G4ENCAddWhite(int iLen)
    {
        while (iLen >= 64) {
            if (iLen >= 2560) {
                G4ENCInsertCode(0x1f, 12); /* Add the 2560 code */
                iLen -= 2560;
            } else {
                int iCode;
                iCode = iLen >> 6; /* Makeup code = mult of 64 */
                G4ENCInsertCode(huff_wmuc[iCode*2], huff_wmuc[iCode*2+1]);
                iLen &= 63; /* Get the remainder */
            }
        }
        /* Add the terminating code */
        G4ENCInsertCode(huff_white[iLen*2], huff_white[iLen*2+1]);
    } /* G4ENCAddWhite() */

    //
    // Internal function to add a BLACK pixel run
    //
    private static void G4ENCAddBlack(int iLen)
    {
        while (iLen >= 64) {
            if (iLen >= 2560) {
                G4ENCInsertCode( 0x1f, 12); /* Add the 2560 code */
                iLen -= 2560;
            } else {
                int iCode;
                iCode = iLen >> 6; /* Makeup code = mult of 64 */
                G4ENCInsertCode(huff_bmuc[iCode*2], huff_bmuc[iCode*2+1]);
                iLen &= 63; /* Get the remainder */
            }
        }
        /* Add the terminating code */
        G4ENCInsertCode(huff_black[iLen*2], huff_black[iLen*2+1]);
    } /* PILAddBlack() */
    //
    // Initialize the compressor
    // This must be called before adding data to the output
    //
    public static int G4ENC_init(int w, int h, int bitdir)
    {
        if (w < 0 || w > 4096 || h < 0 || h > 4096) {
            iError = G4ENC_INVALID_PARAMETER;
            return iError;
        }
        iError = G4ENC_SUCCESS;
        iWidth = w; // image size
        iHeight = h;
        y = 0; // line counter
        iFillOrder = bitdir;
        curLine = new int[w+4];
        iMaxBufSize = h *((w + 7)/8); // max output size is uncompressed size
        outbuf = new byte[iMaxBufSize];
        iOutSize = 0; // no data yet
        for (int i=0; i<w+4; i++) {
            curLine[i] = iWidth;
        }
        ulBits = ulBitOff = 0;
        return iError;
    } /* G4ENC_init() */
    //
    // Internal function to convert uncompressed 1-bit per pixel data
    // into the run-end data needed to feed the G4 encoder
    //
    private static int[] G4ENCEncodeLine(byte[] buf)
    {
        int iCount, xborder;
        int i, c, iOff = 0, iOut = 0;
        int cBits;
        int iLen;
        int x;
        int iRuns[] = new int[iWidth+4];

        xborder = iWidth;
        iCount = (iWidth + 7) >> 3; /* Number of bytes per line */
        cBits = 8;
        iLen = 0; /* Current run length */
        x = 0;

        c = (buf[iOff++] & 0xff);  /* Get the first byte to start */
        iCount--;
        while (iCount >=0) { // outer loop
            while (iCount >= 0) { // white capture loop
                i = bitcount[c]; /* Get the number of consecutive bits */
                iLen += i; /* Add this length to total run length */
                c = (c << i) & 0xff;
                cBits -= i; /* Minus the number in a byte */
                if (cBits <= 0) {
                    iLen += cBits; /* Adjust length */
                    cBits = 8;
                    iCount--;
                    if (iCount >= 0) {
                        c = (buf[iOff++] & 0xff);  /* Get another data byte */
                    }
                    continue; /* Keep doing white until color change */
                }
                if (i == 0) break; // color switched to black
            } // white capture loop
            c ^= 0xff; /* flip color to count black pixels */
            /* Store the white run length */
            xborder -= iLen;
            if (xborder < 0) {
                iLen += xborder; /* Make sure run length is not past end */
                break;
            }
            x += iLen;
            iRuns[iOut++] = x;
            iLen = 0;
            while (iCount >= 0) { // black capture loop
                i = bitcount[c]; /* Get consecutive bits */
                iLen += i; /* Add to total run length */
                c = (c << i) & 0xff;
                cBits -= i;
                if (cBits <= 0) {
                    iLen += cBits; /* Adjust length */
                    cBits = 8;
                    iCount--;
                    if (iCount >= 0) {
                        c = (buf[iOff++] & 0xff);  /* Get another data byte */
                        c ^= 0xff;   /* Flip color to find black */
                    }
                    continue;
                }
                if (i == 0) break; // color switched back to white
            } // black capture loop
            /* Store the black run length */
            c ^= 0xff;       /* Flip color again to find white pixels */
            xborder -= iLen;
            if (xborder < 0) {
                iLen += xborder; /* Make sure run length is not past end */
                break;
            }
            x += iLen;
            iRuns[iOut++] = x;
            iLen = 0;
            } /* while (outer) */

        x += iLen;
        iRuns[iOut++] = x;
        iRuns[iOut++] = x; // Store a few more XSIZE to end the line
        iRuns[iOut++] = x; // so that the compressor doesn't go past
        iRuns[iOut++] = x; // the end of the line
        return iRuns;
    } /* G4ENCEncodeLine() */

    //
    // Reverse the bit order of the data
    //
    static void G4ENCReverse()
    {
        for (int i=0; i<outbuf.length; i++) {
            outbuf[i] = (byte)ucMirror[outbuf[i]];
        }
    } /* G4ENCReverse() */

    //
    // Compress a line of pixels and add it to the output
    // the input format is expected to be MSB (most significant bit) first
    // for example, pixel 0 is in byte 0 at bit 7 (0x80)
    // Returns G4ENC_SUCCESS for each line if all is well and G4ENC_IMAGE_COMPLETE
    // for the last line
    //

    public static int G4ENC_AddLine(byte[] pixels)
    {
        int a0, a0_c, b2, a1;
        int dx;
        int xsize;
        int iCur, iRef, iLen;
        int iHighWater;
        if (pixels == null) {
            iError = G4ENC_INVALID_PARAMETER;
            return iError;
        }
        if (y < 0 || y >= iHeight || curLine == null) {
            iError = G4ENC_NOT_INITIALIZED;
            return iError;
        }

//        if (pImage->ucFillOrder != G4ENC_MSB_FIRST && pImage->ucFillOrder != G4ENC_LSB_FIRST)
//            return G4ENC_NOT_INITIALIZED;
        iError = G4ENC_SUCCESS;
        xsize = iWidth; /* For performance reasons */

        iHighWater = iMaxBufSize - 8;
        // Convert the incoming line of pixels into run-end data
        refLine = curLine; // previous line
        curLine = G4ENCEncodeLine(pixels);

        /* Encode this line as G4 */
        a0 = a0_c = 0;
        iCur = iRef = 0;
        while (a0 < xsize && iOutSize < iHighWater)
        {
            b2 = refLine[iRef+1];
            a1 = curLine[iCur];
            if (b2 < a1) /* Is b2 to the left of a1? */
            {
                /* yes, do pass mode */
                a0 = b2;
                iRef += 2;
                G4ENCInsertCode(1, 4); /* Pass code = 0001 */
            }
            else /* Try vertical and horizontal mode */
            {
                dx = refLine[iRef] - a1;  /* b1 - a1 */
                if (dx > 3 || dx < -3) /* Horizontal mode */
                {
                    G4ENCInsertCode(1, 3); /* Horizontal code = 001 */
                    //    printf("horizontal code\n");
                    if (a0_c != 0) /* If currently black */
                    {
                        G4ENCAddBlack(curLine[iCur] - a0);
                        G4ENCAddWhite(curLine[iCur+1] - curLine[iCur]);
                    }
                    else /* currently white */
                    {
                        G4ENCAddWhite(curLine[iCur] - a0);
                        G4ENCAddBlack(curLine[iCur+1] - curLine[iCur]);
                    }
                        a0 = curLine[iCur+1]; /* a0 = a2 */
                    if (a0 != xsize)
                    {
                        iCur += 2; /* Skip two color flips */
                        while (refLine[iRef] != xsize && refLine[iRef] <= a0)
                            iRef += 2;
                    }
                } /* horizontal mode */
                else /* Vertical mode */
                {
                    dx = (dx + 3) * 2; /* Convert to index table */
                    G4ENCInsertCode(vtable[dx], vtable[dx+1]);
                    a0 = a1;
                    a0_c = 1-a0_c;
                    if (a0 != xsize)
                    {
                        if (iRef != 0)
                            iRef -= 2;
                        iRef++; /* Skip a color change in cur and ref */
                        iCur++;
                        while (refLine[iRef] <= a0 && refLine[iRef] != xsize)
                            iRef += 2;
                    }
                } /* vertical mode */
            } /* horiz/vert mode */
        } /* while x < xsize */
        if (iOutSize >= iHighWater) // need to dump some data
        {
            return G4ENC_DATA_OVERFLOW; // compressed data is larger than uncompressed
        }
        if (y == iHeight-1) { // last line of image
            /* Add two EOL's to the end for RTC */
            G4ENCInsertCode(1, 12); /* EOL */
            G4ENCInsertCode(1, 12); /* EOL */
            G4ENCFlushBits(); // output the final buffered bits
            // wrap up final output
            if (iFillOrder == G4ENC_LSB_FIRST) { // need to reverse the bits
                G4ENCReverse();
            }
            iError = G4ENC_IMAGE_COMPLETE;
        }
        y++; // next line
        return iError;
    } /* G4ENC_addLine() */

    public static int G4ENC_LastError()
    {
        return iError;
    } /* G4ENC_LastError() */

    //
    // Return the final output data
    //
    public static byte[] G4ENC_Output() // return the completed data
    {
        byte[] g4data;
        if (iError != G4ENC_IMAGE_COMPLETE)
            return null; // compression process didn't finish
        g4data = Arrays.copyOfRange(outbuf, 0, iOutSize);
        outbuf = null; // no longer needed
        return g4data;
    } /* G4ENC_Output() */

} /* class G4Encoder() */
