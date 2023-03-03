package com.example.eslimagetransfer;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;

import java.util.Arrays;

//
// EPD_Image class
// Written by Larry Bank
// Copyright (c) 2023 BitBank Software, Inc.
//
// A class for preparing image data for e-paper displays
// Given a bitmap image, a single method will prepare a byte array of data
// to be written directly to the EPD frame buffer
//
public class EPD_Image_Prep {

    public static class CompressedImage {
        public byte[] imagedata;
        public int iType;
    }
    public enum EPDTYPE {
        EPD_BW,
        EPD_BWR,
        EPD_BWY
    }

    private static Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // CREATE A MATRIX FOR THE MANIPULATION
        Matrix matrix = new Matrix();
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight);

        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false);
        return resizedBitmap;
    }
    // Try to compress the image data
    public static CompressedImage CompressImage(byte[] imagedata,  int width, int height, EPDTYPE epd_type)
    {
        CompressedImage ci = new CompressedImage();
        ci.iType = 0; // DEBUG
        return ci;
    } /* CompressImage() */

    //
    // Pass a bitmap of any type or size along with parameters specifying how the image
    // is to be treated. A new bitmap will be returned which is sized to the desired
    // e-paper panel size and the colors will be limited to those available on the panel.
    // Optional Floyd-Steinberg dithering can be used as well.
    //
    public static Bitmap PrepareBitmap(Bitmap bm, EPDTYPE epd_type, boolean bDither, int width, int height ) {

        // Resize the bitmap if needed
        if (bm.getWidth() != width || bm.getHeight() != height) {
            bm = getResizedBitmap(bm, width, height);
        }
        // Reduce the colors and optionally dither
        int x, y;
        int errOffset, cNew, cThresh, lFErr, lFErrR, lFErrG, lFErrB, v=0, h;
        int e1,e2,e3,e4;
        int pixelmask;
        int errors[];

        pixelmask = 0x80;

        if (epd_type == EPDTYPE.EPD_BW) { // Black/white version
            errors = new int[width+3];
            for (y=0; y<height; y++)
            {
                errOffset = 1; // point to second pixel to avoid boundary check
                lFErr = 0;
                for (x=0; x<width; x++)
                {
                    int pixel, A, R, G, B;
                    pixel = bm.getPixel(x, y);
                    A = Color.alpha(pixel);
                    R = Color.red(pixel);
                    G = Color.green(pixel);
                    B = Color.blue(pixel);
                    cNew = (int) (0.2989 * R + 0.5870 * G + 0.1140 * B);
                    if (bDither) {
                        // Add forward error
                        cNew += lFErr;
                        if (cNew > 255) cNew = 255;     // clip to uint8_t
                        else if (cNew < 0) cNew = 0;
                    }
                    // Make a bitonal pixel
                    cThresh = ((cNew & 128) == 128) ? 255 : 0; // threshold to black/white
                    bm.setPixel(x, y, Color.argb(255, cThresh, cThresh, cThresh)); // update bitmap
                    // calculate the Floyd-Steinberg error for this pixel
                    v = cNew - (cNew & pixelmask); // new error for N-bit gray output (always positive)
                    h = v >> 1;
                    e1 = (7*h)>>3;  // 7/16
                    e2 = h - e1;  // 1/16
                    e3 = (5*h) >> 3;   // 5/16
                    e4 = h - e3;  // 3/16
                    // distribute error to neighbors
                    lFErr = e1 + errors[errOffset+1];
                    errors[errOffset+1] = e2;
                    errors[errOffset] += e3;
                    errors[errOffset-1] += e4;
                    errOffset++;
                } // for x
            } // for y
        } else { // black/white/red or yellow
            errors = new int[3*(width+3)];
            for (y=0; y<height; y++)
            {
                int pixel, a, gr, r, g, b, r1, g1, b1;
                errOffset = 3; // point to second pixel to avoid boundary check
                lFErrR = lFErrG = lFErrB = 0;
                for (x=0; x<width; x++)
                {
                    pixel = bm.getPixel(x, y);
                    a = Color.alpha(pixel);
                    r = Color.red(pixel);
                    g = Color.green(pixel);
                    b = Color.blue(pixel); // get color pixel
                    // convert to RGB332 for simplicity
                    if (bDither) { // add errors
                        lFErr = r + lFErrR;
                        if (lFErr < 0) lFErr = 0;
                        else if (lFErr > 255) lFErr = 255;
                        r1 = lFErr;
                        lFErr = g + lFErrG;
                        if (lFErr < 0) lFErr = 0;
                        else if (lFErr > 255) lFErr = 255;
                        g1 = lFErr;
                        lFErr = b + lFErrB;
                        if (lFErr < 0) lFErr = 0;
                        else if (lFErr > 255) lFErr = 255;
                        b1 = lFErr;
                    } else {
                        b1 = b; r1 = r; g1 = g;
                    }
                    gr = (b1 + r1 + g1*2)>>2; // gray
                    // match the color to closest of black/white/red/yellow
                    if (epd_type == EPDTYPE.EPD_BWR) { // black / white /red
                        if (r1 > g1 && r1 > b1) { // red is dominant
                            if (gr < 100 && r1 < 80) {
                                // black
                                b1 = g1 = r1 = 0;
                            } else {
                                if (r1 - b1 > 32 && r1 - g1 > 32) {
                                    // is red really dominant?
                                    b1 = g1 = 0;
                                    r1 = 0xff;
                                } else { // yellowish should be white
                                    // no, use white instead of pink/yellow
                                    b1 = g1 = r1 = 0xff;
                                }
                            }
                        } else { // check for white/black
                            if (gr >= 100) {
                                b1 = g1 = r1 = 0xff;
                            } else {
                                // black
                                b1 = g1 = r1 = 0;
                            }
                        }
                    } else { // must be black / white / yellow
                        if (r1 > b1 && g1 > b1) { // yellow is dominant?
                            if (gr < 100 && r1 < 80) {
                                // black
                                b1 = g1 = r1 = 0;
                            } else {
                                if (r1 - b1 > 32 && g1 - b1 > 32) {
                                    // is yellow really dominant?
                                    b1 = 0;
                                    r1 = g1 = 0xff;
                                } else { // yellowish should be white
                                    // no, use white instead of pink/yellow
                                    b1 = g1 = r1 = 0xff;
                                }
                            }
                        } else { // check for white/black
                            if (gr >= 100) {
                                b1 = g1 = r1 = 0xff;
                            } else {
                                // black
                                b1 = g1 = r1 = 0;
                            }
                        }
                    }
                    bm.setPixel(x, y, Color.argb(0xff, r1, g1, b1)); // put back new color
                    if (bDither) {
                        // accumulate the R/G/B error of the matched color vs original
                        // calculate the Floyd-Steinberg error for this pixel
                        v = (r - r1); // new error for red
                        h = v >> 1;
                        e1 = (7*h)>>3;  // 7/16
                        e2 = h - e1;  // 1/16
                        e3 = (5*h) >> 3;   // 5/16
                        e4 = h - e3;  // 3/16
                        // distribute error to neighbors
                        lFErrR = e1 + errors[errOffset+3];
                        errors[errOffset+3] = e2;
                        errors[errOffset] += e3;
                        errors[errOffset-3] += e4;
                        v = (g - g1); // new error for green
                        h = v >> 1;
                        e1 = (7*h)>>3;  // 7/16
                        e2 = h - e1;  // 1/16
                        e3 = (5*h) >> 3;   // 5/16
                        e4 = h - e3;  // 3/16
                        // distribute error to neighbors
                        lFErrG = e1 + errors[errOffset+4];
                        errors[errOffset+4] = e2;
                        errors[errOffset+1] += e3;
                        errors[errOffset-2] += e4;
                        v = (b - b1); // new error for blue
                        h = v >> 1;
                        e1 = (7*h)>>3;  // 7/16
                        e2 = h - e1;  // 1/16
                        e3 = (5*h) >> 3;   // 5/16
                        e4 = h - e3;  // 3/16
                        // distribute error to neighbors
                        lFErrB = e1 + errors[errOffset+5];
                        errors[errOffset+5] = e2;
                        errors[errOffset+2] += e3;
                        errors[errOffset-1] += e4;
                        errOffset += 3;
                    }
                } // for x
            } // for y
        } // bwr or bwy
        return bm;
    } /* PrepareBitmap() */

    //
    // Compress a buffer of image data using the PCX algorithm (modified run-length)
    //
    public static byte[] CompressPCX(byte[] uncompressed)
    {
      byte[] output;

        if (uncompressed == null) return null;
        output = new byte[uncompressed.length];
        int iHighwater = uncompressed.length-32; // safe limit of output data size
        int iRepeat = 1; // current repeat count
        int i, iLen = 0; // length of output
        int c, cCompare;

        cCompare = uncompressed[0];
        for (i=1; i<uncompressed.length && iLen < iHighwater; i++)
        {
            c = uncompressed[i];
            if (c == cCompare) // a repeat byte?
            {
                iRepeat++;
            }
            else
            {
                while (iRepeat > 63) // store max repeat count
                {
                    output[iLen++] = -1; // max repeat count = 63;
                    output[iLen++] = (byte)cCompare; // byte to repeat
                    iRepeat -= 63;
                }
                if (iRepeat > 1) // more than 1 of the same byte, store it
                {
                    output[iLen++] = (byte)(iRepeat | -64); // 0xc0
                    output[iLen++] = (byte)cCompare;
                }
                else // single byte to store
                {
                    if (cCompare >= -64 && cCompare < 0) // same characteristics as repeat code, special case
                    {
                        output[iLen++] = -63; // store as a repeat of 1 (0xc1)
                        output[iLen++] = (byte)cCompare;
                    }
                    else
                        output[iLen++] = (byte)cCompare; // just store the byte
                }
                iRepeat = 1;   // new repeat count of 1
                cCompare = c; // the current byte becomes the one to repeat
            }
        } // for each byte of image
        if (iLen >= iHighwater) return null; // something went wrong, abort
        return Arrays.copyOfRange(output, 0, iLen-1); // resize output array to the data generated
    } /* CompressPCX() */

    //
    // Compress a buffer of image data using the CCITT G4 (T.6) algorithm
    //
    public static byte[] CompressG4(byte[] uncompressed, int width, int height, EPDTYPE epd_type, int iOrientation)
    {
        int w, h;

        if (iOrientation == 0) {
            w = width;
            h = height;
        } else {
            w = height;
            h = width;
        }
        h *= (epd_type == EPDTYPE.EPD_BW) ? 1:2; // 1 or 2 planes of data
        if (G4Encoder.G4ENC_init(w, h, G4Encoder.G4ENC_MSB_FIRST) == G4Encoder.G4ENC_SUCCESS) {
            byte[] line;
            int iPitch = (w + 7)/8;
            for (int y=0; y<h && G4Encoder.G4ENC_LastError() == G4Encoder.G4ENC_SUCCESS; y++) {
                line = Arrays.copyOfRange(uncompressed, iPitch*y, iPitch*(y+1));
                G4Encoder.G4ENC_AddLine(line);
            }
            if (G4Encoder.G4ENC_LastError() == G4Encoder.G4ENC_IMAGE_COMPLETE)
                return G4Encoder.G4ENC_Output();
        }
        return null;
    } /* CompressG4() */

    //
    // Compress a buffer of image data using the PackBits algorithm (modified run-length)
    //
    public static byte[] CompressPackBits(byte[] uncompressed)
    {
        byte[] output = new byte[uncompressed.length * 2]; // DEBUG
        int iOffset = 0, iLen = 0; // output size
        int o, c=0, iStart = 0;
        int iCount; // repeat or non-repeat count

        iStart = iOffset; /* Preserve starting point */
        iCount = 1; /* Repeat count */
        o = uncompressed[iOffset++];  /* Set the compare byte */
        while (iOffset < uncompressed.length) {
            // look for repeating bytes
            check_repeats:
            while (iOffset < uncompressed.length) {
                c = uncompressed[iOffset++];
                if (c == o) {
                    iCount++;
                } else {
                    break check_repeats;
                }
            } // while looking for repeats
            if (iCount > 1) { /* Any repeats? */
                while (iCount > 127) {
                    output[iLen++] = (byte) 0x81; /* Store max count */
                    output[iLen++] = (byte) o; /* Store the repeating byte */
                    iCount -= 128;
                }
                if (iCount > 1) {
                    output[iLen++] = (byte) (1 - iCount);
                    output[iLen++] = (byte) o;
                } else if (iCount == 1) { // 1 leftover, use it in the next non-repeat group
                    iOffset--;
                    c = uncompressed[iOffset-1];
                }
                iCount = 1;
                o = c; // new compare byte
            } else {
                iOffset--; // back up over the last byte read
            }
            if (iOffset < uncompressed.length) {
                /* Look for non-repeats */
                check_non_repeats:
                while (iOffset < uncompressed.length) {
                    c = uncompressed[iOffset++];
                    if (c != o) {
                        iCount++;
                        o = c;
                    } else {
                        iCount--; // use the matching ones as a starting match
                        iOffset--;
                        break check_non_repeats;
                    }
                }
                if (iOffset == uncompressed.length && uncompressed[iOffset - 2] == uncompressed[iOffset - 1]) { // last byte
                    iCount++; // treat it as another non-repeat
                    iOffset -= iCount; // point to start of non-repeating sequence
                } else {
                    iOffset -= (iCount + 1); // point to start of non-repeating sequence
                }
                /* Store the non-repeats */
                while (iCount > 127) {
                    output[iLen++] = (byte) 127; /* Store max repeat count of 127 */
                    for (int i = 0; i < 128; i++) {
                        output[iLen++] = uncompressed[iOffset++];
                    }
                    iCount -= 128;
                }
                if (iCount > 0) // remaining non-repeats
                {
                    output[iLen++] = (byte) (iCount - 1);
                    for (int i = 0; i < iCount; i++) {
                        output[iLen++] = uncompressed[iOffset++];
                    }
                }
            } // need to check offset again before looking for non-repeats
            iCount = 1;
            iOffset++; // prepare for next repeats search
        } // outer loop
        return Arrays.copyOfRange(output, 0, iLen);
    } /* CompressPackBits() */

    //
    // Convert a bitmap into a byte array containing the data to be written directly into
    // the EPD framebuffer
    //
    public static byte[] PrepareImageData(Bitmap bm, EPDTYPE epd_type, int iOrientation, boolean bInvert)
    {
        int x, y, iPitch, iWidth, iHeight, iPlanes = (epd_type == EPDTYPE.EPD_BW) ? 1 : 2;
        byte[] imagedata;
        int color, b0, b1, u8Invert, mask, iOffset, iPlaneOffset;
        int colorMatch;
        u8Invert = (bInvert == true) ? 0xff : 0x00;
        if (epd_type == EPDTYPE.EPD_BWR)
            colorMatch = Color.argb(0, 0xff, 0, 0); // red
        else
            colorMatch = Color.argb( 0, 0xff, 0xff, 0); // yellow

        if (iOrientation == 0) {
            iWidth = bm.getWidth();
            iHeight = bm.getHeight();
        } else {
            iWidth = bm.getHeight();
            iHeight = bm.getWidth();
        }
        iPitch = (iWidth + 7)/8; // bytes per line
        iPlaneOffset = (iPitch * iHeight);
        imagedata = new byte[iPitch * iHeight * iPlanes]; // uncompressed size of output

        if (iPlanes == 1) { // 1-bpp
            for (y = 0; y < iHeight; y++) {
                iOffset = iPitch * y;
                b0 = 0;
                mask = 0x80; // destination bit
                for (x = 0; x < iWidth; x++) {
                    if (iOrientation == 0)
                        color = bm.getPixel(x, y);
                    else // rotated 90 clockwise
                        color = bm.getPixel(iHeight-1-y, x);
                    if ((color & 0xffffff) != 0) // non-black pixel
                        b0 |= mask;
                    mask >>= 1;
                    if (mask == 0) { // store the byte
                        b0 = b0 ^ u8Invert;
                        imagedata[iOffset++] = (byte)b0;
                        mask = 0x80;
                        b0 = 0; // reset for the next byte
                    }
                } // for x
                if ((iWidth & 7) != 0) { // width is not an even multiple of bytes
                    b0 = b0 ^u8Invert;
                    imagedata[iOffset] = (byte)b0; // store partial byte
                }
            } // for y
        } else { // 2-plane
            for (y = 0; y < iHeight; y++) {
                iOffset = iPitch * y;
                b0 = b1 = 0;
                mask = 0x80; // destination bit
                for (x = 0; x < iWidth; x++) {
                    if (iOrientation == 0)
                        color = bm.getPixel(x, y);
                    else // rotated 90 clockwise
                        color = bm.getPixel(y, iWidth-1-x) & 0xffffff; // remove alpha
                    if (color == 0xffffff) { // white
                        b0 |= mask; // plane 0
                    } else if (color == colorMatch) {
                        b1 |= mask; // plane 1 (red or yellow)
                    }
                    mask >>= 1;
                    if (mask == 0) { // store the byte
                        b0 = b0 ^u8Invert;
                        b1 = b1 ^u8Invert;
                        imagedata[iOffset] = (byte) b0;
                        imagedata[iOffset + iPlaneOffset] = (byte) b1;
                        iOffset++;
                        mask = 0x80;
                        b0 = b1 = 0; // reset for the next byte(s)
                    }
                } // for x
                if ((iWidth & 7) != 0) { // width is not an even multiple of bytes
                    b0 = b0 ^u8Invert;
                    b1 = b1 ^u8Invert;
                    imagedata[iOffset] = (byte) b0; // store partial bytes
                    imagedata[iOffset + iPlaneOffset] = (byte) b1;
                }
            } // for y
        } // 2-plane
        return imagedata;
    } /* PrepareImageData() */

} /* EPD_Image_Prep class */
