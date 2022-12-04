//
//  ViewController.m
//  Image2ESL
//
//  Created by Larry Bank
//  Copyright (c) 2021 BitBank Software Inc. All rights reserved.
//

#import "ViewController.h"
#import "MyBLE.h"
#include "G4ENCODER.h"

G4ENCIMAGE g4;

MyBLE *BLEClass;
uint8_t contrast_lookup[256];
static uint8_t *pDithered = NULL; // Dithered image data ready to print
static int iWidth, iHeight; // size of the image that's ready to print

const char *szPanelNames[] = {  "EPD42_400x300", // WFT0420CZ15
  "EPD29_128x296",
  "EPD29B_128x296",
  "EPD29R_128x296",
  "EPD293_128x296",
  "EPD42R_400x300",
  "EPD42R2_400x300",
  "EPD213B_104x212",
  "EPD213R_104x212",
  "EPD213R_104x212_d",
  "EPD213_104x212",
  "EPD213_122x250", // waveshare
  "EPD213B_122x250", // GDEY0213B74
  "EPD154_152x152", // GDEW0154M10
  "EPD154R_152x152",
  "EPD154_200x200", // waveshare
  "EPD27_176x264", // waveshare
  "EPD27b_176x264", // GDEY027T91
  "EPD266_152x296", // GDEY0266T90
  "EPD579_792x272", // GDEY0579T93
  "EPD583R_600x448",
  "EPD74R_640x384",
};
const uint8_t u8PanelColors[] = {2,2,2,3,2,3,3,2,3,3,2,2,2,2,3,2,2,2,2,2,3,3};

const int iPanelWidths[] = {400, 296, 296, 296, 296, 400, 400, 212, 212, 212, 212, 250, 250, 152, 152, 200, 264, 264, 296, 792, 600, 640};
const int iPanelHeights[] = {300, 128, 128, 128, 128, 300, 300, 104, 104, 104, 104, 122, 122, 152, 152, 200, 176, 176, 152, 272, 448, 384};

@implementation ViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    _myview = [DragDropView alloc];
}

- (void)viewDidLayout {
    // the outer frame size is known here, so set our drag/drop frame to the same size
    
//    _myview.frame = NSMakeRect(0, 0, self.view.frame.size.width, self.view.frame.size.height);
    
    [_myview initWithFrame:CGRectMake(0, 0, self.view.frame.size.width, self.view.frame.size.height)];
    // Do any additional setup after loading the view.
    BLEClass = [[MyBLE alloc] init];
    
    _myview.myVC = self; // give DragDropView access to our methods
    [[self view] addSubview:_myview];

    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(ditherFile:)
                                                 name:@"PrintFileNotification"
                                               object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(statusChanged:)
                                                 name:@"StatusChangedNotification"
                                               object:nil];

//    [BLEClass startScan]; // scan and connect to any printers in the area

}
- (void)setRepresentedObject:(id)representedObject {
    [super setRepresentedObject:representedObject];

    // Update the view, if already loaded.
}
- (IBAction)FeedPushed:(NSButton *)sender {
    NSLog(@"Erase!");
    uint8_t ucTemp[4];
    ucTemp[0] = 0x00; // erase memory
    ucTemp[1] = 0xff; // to white
    [BLEClass writeData:ucTemp withLength:2 withResponse:NO];
    [NSThread sleepForTimeInterval: 0.01];
    ucTemp[0] = 0x01; // send memory image to EPD
    [BLEClass writeData:ucTemp withLength:1 withResponse:NO];
}
- (IBAction)ConnectPushed:(NSButton *)sender {
    NSLog(@"Connect!");
    if (![BLEClass isConnected]) {
        [BLEClass startScan];
    }
}

- (IBAction)TransmitPushed:(NSButton *)sender {
    NSLog(@"Send!");
    [self sendImage];
}

// Process a new file
- (void)processFile:(NSString *)path
{
    _filename = [[NSString alloc] initWithString:path];
    NSLog(@"User dropped file %@", _filename);

} /* processFile */

- (uint8_t *)DitherImage:(uint8_t*)pPixels width:(int)iWidth height:(int)iHeight pitch:(int)iSrcPitch dither:(bool)bDither
{
    int x, y, iOffset, xmask=0, iDestPitch=0;
    int32_t cNew, lFErr, lFErrR, lFErrG, lFErrB, v=0, h;
    int32_t e1,e2,e3,e4;
    uint8_t cOut, cOut1; // forward errors for gray
    uint8_t *pSrc, *pDest, *errors, *pErrors=NULL, *d, *s; // destination 8bpp image
    uint8_t pixelmask=0, shift=0;
    uint8_t ucTemp[400];
    int32_t iErrors[400*3];
    errors = ucTemp; // plenty of space here for the bitmaps we'll generate
    memset(ucTemp, 0, sizeof(ucTemp));
    pSrc = pPixels; // write the new pixels over the original
    iDestPitch = (iWidth+7)/8;
    iOffset = iDestPitch * iHeight; // offset to the second (red) bit plane
    pDest = (uint8_t *)malloc(iDestPitch * iHeight * 2);
    pixelmask = 0x80;
    shift = 1;
    xmask = 7;
    if (u8PanelColors[BLEClass.iPanelType] == 2) { // Black/white version
        for (y=0; y<iHeight; y++)
        {
            s = &pSrc[y * iSrcPitch];
            d = &pDest[y * iDestPitch];
            pErrors = &errors[1]; // point to second pixel to avoid boundary check
            lFErr = 0;
            cOut = 0;
            for (x=0; x<iWidth; x++)
            {
                cNew = *s++; // get grayscale uint8_t pixel
                if (bDither) {
                    cNew = (cNew * 2)/3; // make white end of spectrum less "blown out"
                    // add forward error
                    cNew += lFErr;
                    if (cNew > 255) cNew = 255;     // clip to uint8_t
                }
                cOut <<= shift;                 // pack new pixels into a byte
                cOut |= (cNew >> (8-shift));    // keep top N bits
                if ((x & xmask) == xmask)       // store it when the byte is full
                {
                    *d++ = ~cOut; // color is inverted
                    cOut = 0;
                }
                // calculate the Floyd-Steinberg error for this pixel
                v = cNew - (cNew & pixelmask); // new error for N-bit gray output (always positive)
                h = v >> 1;
                e1 = (7*h)>>3;  // 7/16
                e2 = h - e1;  // 1/16
                e3 = (5*h) >> 3;   // 5/16
                e4 = h - e3;  // 3/16
                // distribute error to neighbors
                lFErr = e1 + pErrors[1];
                pErrors[1] = (uint8_t)e2;
                pErrors[0] += e3;
                pErrors[-1] += e4;
                pErrors++;
            } // for x
            cOut <<= (8-(x & 7));
            *d++ = ~cOut; // store partial byte
        } // for y
    } else { // black/white/red
        memset(iErrors, 0, sizeof(iErrors));
        int32_t *pErr;
        for (y=0; y<iHeight; y++)
        {
            uint8_t gr, r, g, b, r1, g1, b1;
            s = &pSrc[y * iSrcPitch];
            d = &pDest[y * iDestPitch];
            pErr = &iErrors[3]; // point to second pixel to avoid boundary check
            lFErrR = lFErrG = lFErrB = 0;
            cOut = cOut1 = 0;
            for (x=0; x<iWidth; x++)
            {
                cOut <<= 1;
                cOut1 <<= 1;
                r = *s++; g = *s++; b = *s++; s++; // get color pixel
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
                // match the color to closest of black/white/red
                if (r1 > g1 && r1 > b1) { // red is dominant
                    if (gr < 100 && r1 < 80) {
                        // black
                        b1 = g1 = r1 = 0;
                    } else {
                        if (r1-b1 > 32 && r1-g1 > 32) {
                            // is red really dominant?
                            cOut1 |= 1; // red
                            b1 = g1 = 0; r1 = 0xff;
                        } else { // yellowish should be white
                            // no, use white instead of pink/yellow
                            cOut |= 1;
                            b1 = g1 = r1 = 0xff;
                        }
                    }
                } else { // check for white/black
                    if (gr >= 100) {
                        cOut |= 1; // white
                        b1 = g1 = r1 = 0xff;
                    } else {
                        // black
                        b1 = g1 = r1 = 0;
                    }
                }
                if (bDither) {
                    // accumulate the R/G/B error of the matched color vs original
                    // calculate the Floyd-Steinberg error for this pixel
                    v = (int32_t)(r - r1); // new error for red
                    h = v >> 1;
                    e1 = (7*h)>>3;  // 7/16
                    e2 = h - e1;  // 1/16
                    e3 = (5*h) >> 3;   // 5/16
                    e4 = h - e3;  // 3/16
                    // distribute error to neighbors
                    lFErrR = e1 + pErr[3];
                    pErr[3] = e2;
                    pErr[0] += e3;
                    pErr[-3] += e4;
                    v = (int32_t)(g - g1); // new error for green
                    h = v >> 1;
                    e1 = (7*h)>>3;  // 7/16
                    e2 = h - e1;  // 1/16
                    e3 = (5*h) >> 3;   // 5/16
                    e4 = h - e3;  // 3/16
                    // distribute error to neighbors
                    lFErrG = e1 + pErr[4];
                    pErr[4] = e2;
                    pErr[1] += e3;
                    pErr[-2] += e4;
                    v = (int32_t)(b - b1); // new error for blue
                    h = v >> 1;
                    e1 = (7*h)>>3;  // 7/16
                    e2 = h - e1;  // 1/16
                    e3 = (5*h) >> 3;   // 5/16
                    e4 = h - e3;  // 3/16
                    // distribute error to neighbors
                    lFErrB = e1 + pErr[5];
                    pErr[5] = e2;
                    pErr[2] += e3;
                    pErr[-1] += e4;
                    pErr += 3;
                }
                if ((x & xmask) == xmask)       // store it when the byte is full
                {
                    d[iOffset] = cOut1; // red plane is not inverted
                    *d++ = ~cOut; // color is inverted
                    cOut = cOut1 = 0;
                }
                // calculate the Floyd-Steinberg error for this pixel
                //v = cNew - (cNew & pixelmask); // new error for N-bit gray output (always positive)
                //h = v >> 1;
                //e1 = (7*h)>>3;  // 7/16
                //e2 = h - e1;  // 1/16
                //e3 = (5*h) >> 3;   // 5/16
                //e4 = h - e3;  // 3/16
                // distribute error to neighbors
                //lFErr = e1 + pErrors[1];
                //pErrors[1] = (uint8_t)e2;
                //pErrors[0] += e3;
                //pErrors[-1] += e4;
                //pErrors++;
            } // for x
//            cOut <<= (8-(x & 7));
//            cOut1 <<= (8-(x & 7));
            d[iOffset] = cOut1;
            *d++ = ~cOut; // store partial byte
        } // for y
    }
    return pDest;
} /* DitherImage */

- (void) getRotatedLine:(uint8_t *)s current_x:(int)x destination:(uint8_t *)d
{
    uint8_t uc ,ucSrcMask, ucDestMask;
    int iHeightMult = (u8PanelColors[BLEClass.iPanelType] == 2) ? 1:2;
    int y;
    ucSrcMask = (0x80 >> (x & 7));
    uc = 0xff;
    ucDestMask = 0x80;
    for (y=0; y<iHeight*iHeightMult; y++) { // form the bytes of each line
        if (s[0] & ucSrcMask)
            uc &= ~ucDestMask;
        ucDestMask >>= 1;
        if (ucDestMask == 0) {
            *d++ = uc; // store 8 bits of output
            uc = 0xff;
            ucDestMask = 0x80;
        }
        s += ((iWidth+7)>>3);
    } // for y
    *d++ = uc; // store last partial byte
} /* getRotatedLine() */
//
// Send the image that was previously dithered
// to the connected ESL
//
- (void)sendImage
{
    uint8_t *s, *d, ucTemp[256]; // holds each packet to send
    int x, y, iLen, iTotal;
    int iHeightMult = (u8PanelColors[BLEClass.iPanelType] == 2) ? 1:2;
    int iPitch = ((iHeight*iHeightMult)+7)/8;
    if (pDithered == NULL) return; // no image to send
    
    // Now send it to the ESL
    ucTemp[0] = 0x02; // set byte pos
    ucTemp[1] = ucTemp[2] = 0; // start of image
    [BLEClass writeData:ucTemp withLength:3 withResponse:NO];
    [NSThread sleepForTimeInterval: 0.01];
    if (_DitherCheck.state == NSControlStateValueOff) { // try compressing as CCITT G4
        uint8_t *pOut = malloc(65536); // max reasonable size
        int rc = G4ENC_init(&g4, iHeight*iHeightMult, iWidth, G4ENC_MSB_FIRST, NULL, pOut, 65536);
        if (rc == G4ENC_SUCCESS) {
            for (x=iWidth-1; x>=0 && rc == G4ENC_SUCCESS; x--) {
                // rotate the image 90
                s = &pDithered[x >> 3];
                [self getRotatedLine: s current_x:x destination:ucTemp];
                rc = G4ENC_addLine(&g4, ucTemp);
            } // for each line of image
        } // successful init
        iTotal = G4ENC_getOutSize(&g4);
        NSLog(@"G4 compressed size = %d", iTotal);
        if (iTotal < iPitch * iWidth) { // G4 beat uncompressed
            ucTemp[0] = 0x04; // compressed image data
            x = iTotal;
            y = 0;
            while (x) { // send in relatively large blocks
                iLen = 199;
                if (iLen > x) iLen = x;
                memcpy(&ucTemp[1], &pOut[y], iLen); // send up to 200 bytes total per write
                [BLEClass writeData:ucTemp withLength:(iLen+1) withResponse:YES];
                x -= iLen;
                y += iLen;
            }
            ucTemp[0] = 0x01; // display the new image data
            [BLEClass writeData:ucTemp withLength:1 withResponse:YES];
            return; // all done
        } // G4 beats uncompressed
    }
    // transmit it rotated since the memory is laid out 90 degrees clockwise rotated
    iTotal = 0;
    ucTemp[0] = 0x03; // image data
    d = &ucTemp[1];
    for (x=iWidth-1; x>=0; x--) {
        s = &pDithered[x>>3];
        [self getRotatedLine: s current_x:x destination: d];
        d += iPitch;
        if (d - ucTemp >= 200) {
            [BLEClass writeData:ucTemp withLength:(int)(d-ucTemp) withResponse:YES];
            d = &ucTemp[1];
        }
        iTotal += iPitch;
//        [NSThread sleepForTimeInterval: 0.01];
    } // for x
    if (d-ucTemp > 1) { // send the last block
        [BLEClass writeData:ucTemp withLength:(int)(d-ucTemp) withResponse:YES];
    }
    NSLog(@"Total image bytes sent = %d", iTotal);
    ucTemp[0] = 0x01; // display the new image data
    [BLEClass writeData:ucTemp withLength:1 withResponse:NO];
} /* sendImage */

- (void)statusChanged:(NSNotification *) notification
{
    if ([BLEClass isConnected]) {
        _StatusLabel.stringValue = [NSString stringWithFormat:@"Connected to: %@, %d x %d, %s", [BLEClass getName], iPanelWidths[BLEClass.iPanelType], iPanelHeights[BLEClass.iPanelType], (u8PanelColors[BLEClass.iPanelType] == 2) ? "BW": "BWR"];
    } else {
        _StatusLabel.stringValue = @"Disconnected";
    }
} /* statusChanged */

- (void)ditherFile:(NSNotification *) notification
{
    // load the file into an image object
    NSData *theFileData = [[NSData alloc] initWithContentsOfFile:_filename options: NSDataReadingMappedAlways error: nil]; // read file into memory
    if (theFileData) {
        // decode the image into a bitmap
        NSBitmapImageRep *bitmap = [[NSBitmapImageRep alloc] initWithData:theFileData];
        if (bitmap) {
            uint8_t u8NumColors = u8PanelColors[BLEClass.iPanelType];
            int iOriginalWidth, iOriginalHeight, iPlanes;
            iOriginalWidth = bitmap.size.width;
            iOriginalHeight = bitmap.size.height;
            iWidth = iPanelWidths[BLEClass.iPanelType]; // ESL width in pixels
            iHeight = iPanelHeights[BLEClass.iPanelType];
            iPlanes = ((u8NumColors == 2) ? 1:4);
            NSSize newSize;
            NSColorSpace *targetColorSpace;
            NSBitmapImageRep *grayBitmap;
            newSize.width = iWidth;
            newSize.height = iHeight;
            if (u8NumColors == 2) { // make it gray for black/white e-papers
                // convert to grayscale
                targetColorSpace = [NSColorSpace genericGrayColorSpace];
            } else {
                targetColorSpace = [NSColorSpace genericRGBColorSpace]; // leave as color
            }
            grayBitmap = [bitmap bitmapImageRepByConvertingToColorSpace: targetColorSpace renderingIntent: NSColorRenderingIntentDefault];
            // now resize it
            NSBitmapImageRep *rep = [[NSBitmapImageRep alloc]
                      initWithBitmapDataPlanes:NULL
                                    pixelsWide:newSize.width
                                    pixelsHigh:newSize.height
                                 bitsPerSample:8
                                 samplesPerPixel:iPlanes
                                      hasAlpha:(iPlanes == 4)
                                      isPlanar:NO
                                     colorSpaceName:((u8NumColors == 2) ? NSCalibratedWhiteColorSpace : NSCalibratedRGBColorSpace)
                                   bytesPerRow:newSize.width * iPlanes
                                  bitsPerPixel:8*iPlanes];
            rep.size = newSize;
            [NSGraphicsContext saveGraphicsState];
            [NSGraphicsContext setCurrentContext:[NSGraphicsContext graphicsContextWithBitmapImageRep:rep]];
            [grayBitmap drawInRect:NSMakeRect(0, 0, newSize.width, newSize.height)];
            [NSGraphicsContext restoreGraphicsState];
            uint8_t *pPixels = [rep bitmapData];
            uint8_t *pGray;
            int x, y;
            uint8_t c, ucMask, *s, *d;
            if (pDithered) free(pDithered);
            bool bDither = (_DitherCheck.state == NSControlStateValueOn);
            pDithered = [self DitherImage:pPixels width:iWidth height:iHeight pitch:(int)rep.bytesPerRow dither:bDither];
            // Create a preview image
            pGray = (uint8_t *)malloc((iWidth+7) * iHeight * iPlanes);
            if (iPlanes ==  1) {
                // convert the 1-bpp image to 8-bit grayscale so that we can show it in the preview window
                for (y=0; y<iHeight; y++) {
                    s = &pDithered[y * ((iWidth+7)/8)];
                    d = &pGray[iWidth * y];
                    ucMask = 0x80;
                    c = *s++;
                    for (x=0; x<iWidth; x++) {
                        if (c & ucMask)
                            *d++ = 0;
                        else
                            *d++ = 0xff;
                        ucMask >>= 1;
                        if (ucMask == 0) {
                            ucMask = 0x80;
                            c = *s++;
                        }
                    } // for x
                } // for y
            } else { // must be B/W/R
                // convert the 2 planes of 1-bpp image to 32-bit RGBA image so that we can show it in the preview window
                int iOffset = iHeight * ((iWidth+7)/8);
                uint8_t c1;
                for (y=0; y<iHeight; y++) {
                    s = &pDithered[y * ((iWidth+7)/8)];
                    d = &pGray[iWidth * y * iPlanes];
                    ucMask = 0x80;
                    c1 = s[iOffset]; // red plane
                    c = *s++; // black/white plane
                    for (x=0; x<iWidth; x++) {
                        if (c1 & ucMask) { // red has priority over B/W
                            *d++ = 0xff; *d++ = 0; *d++ = 0; *d++ = 0xff; // store red
                        } else {
                            if (c & ucMask) {
                                *d++ = 0; *d++ = 0; *d++ = 0; *d++ = 0xff; // black
                            } else {
                                *d++ = 0xff; *d++ = 0xff; *d++ = 0xff; *d++ = 0xff; // white
                            }
                        }
                        ucMask >>= 1;
                        if (ucMask == 0) {
                            ucMask = 0x80;
                            c1 = s[iOffset];
                            c = *s++;
                        }
                    } // for x
                } // for y
            }
            // make an NSImage out of the grayscale bitmap
            CGColorSpaceRef colorSpace;
            CGContextRef gtx;
            NSUInteger bitsPerComponent = 8;
            NSUInteger bytesPerRow = iWidth * iPlanes;
            if (iPlanes == 1) {
                colorSpace = CGColorSpaceCreateDeviceGray();
                gtx = CGBitmapContextCreate(pGray, iWidth, iHeight, bitsPerComponent, bytesPerRow, colorSpace, kCGBitmapByteOrderDefault | kCGImageAlphaNone);
            } else {
                colorSpace = CGColorSpaceCreateDeviceRGB();
                gtx = CGBitmapContextCreate(pGray, iWidth, iHeight, bitsPerComponent, bytesPerRow, colorSpace, kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big);
            }
            CGImageRef myimage = CGBitmapContextCreateImage(gtx);
//            CGContextSetInterpolationQuality(gtx, kCGInterpolationNone);
            NSImage *image = [[NSImage alloc]initWithCGImage:myimage size:NSZeroSize];
            _myImage.image = image; // set it into the image view
            // Free temp objects
            CGColorSpaceRelease(colorSpace);
            CGContextRelease(gtx);
            CGImageRelease(myimage);
            free(pGray);
        }
    }
} /* ditherFile*/
@end
