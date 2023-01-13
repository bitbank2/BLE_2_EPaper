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
#include "PNGenc.h"
G4ENCIMAGE g4;
PNGIMAGE png;

// max compressed data size
#define OUTBUFFER_SIZE 65536
// BLE command bytes (1st byte of each packet)
enum {
        BLE_CMD_CLEAR=0,
        BLE_CMD_UNCOMPRESSED,
        BLE_CMD_G4,
        BLE_CMD_PCX,
        BLE_CMD_PNG,
        BLE_CMD_GFX_CMDS,
        BLE_CMD_COUNT
};
#define BLE_FIRST_PACKET 0x40
#define BLE_LAST_PACKET 0x80

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
  "EPD35Y_184x384", // Hanshow Nebular black/white/yellow (#22)
};
const uint8_t u8PanelColors[] = {2,2,2,3,2,3,3,2,3,3,2,2,2,2,3,2,2,2,2,2,3,3,3};
const uint8_t u8IsRotated[] =   {0,1,1,1,1,0,0,1,1,1,1,1,1,1,0,0,0,1,1,1,1,0,1};

const int iPanelWidths[] = {400, 296, 296, 296, 296, 400, 400, 212, 212, 212, 212, 250, 250, 152, 152, 200, 264, 264, 296, 792, 600, 640, 384};
const int iPanelHeights[] = {300, 128, 128, 128, 128, 300, 300, 104, 104, 104, 104, 122, 122, 152, 152, 200, 176, 176, 152, 272, 448, 384, 184};

@implementation ViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    _myview = [DragDropView alloc];
}

- (void)viewDidLayout {
    // the outer frame size is known here, so set our drag/drop frame to the same size
    
//    _myview.frame = NSMakeRect(0, 0, self.view.frame.size.width, self.view.frame.size.height);
    
    DragDropView *ddv = [_myview initWithFrame:CGRectMake(0, 0, self.view.frame.size.width, self.view.frame.size.height)]; // don't need to save the pointer to the view
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
- (IBAction)DitherPushed:(NSButton *)sender {
    [self ditherFile:nil];
}

- (IBAction)FeedPushed:(NSButton *)sender {
    NSLog(@"Erase!");
    uint8_t ucTemp[4];
    ucTemp[0] = BLE_CMD_CLEAR; // erase memory
    ucTemp[1] = 0xff; // to white
    [BLEClass writeData:ucTemp withLength:2 withResponse:YES];
//    [NSThread sleepForTimeInterval: 0.01];
//    ucTemp[0] = BLE_CMD_DISPLAY; // send memory image to EPD
//    [BLEClass writeData:ucTemp withLength:1 withResponse:YES];
}
- (IBAction)ConnectPushed:(NSButton *)sender {
    if (![BLEClass isConnected]) {
        NSLog(@"Connect!");
        [BLEClass startScan];
    } else {
        NSLog(@"Disconnect!");
        [BLEClass disconnect];
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
    uint8_t ucTemp[1024];
    int32_t iErrors[1024*3];
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
    {
        uint8_t *pG4Out, *pPCXOut, *pPNGOut;
        int iPCXTotal, iG4Total, iPNGTotal;
        pPCXOut = malloc(OUTBUFFER_SIZE); // max reasonable size
        pG4Out = malloc(OUTBUFFER_SIZE);
        pPNGOut = malloc(OUTBUFFER_SIZE);
        // compare the compressed data size
        iPCXTotal = [self compressPCX:pDest dest:pPCXOut];
        iG4Total = [self compressG4:pDest dest:pG4Out];
        iPNGTotal = [self compressPNG:pDest dest:pPNGOut];
        sprintf((char *)ucTemp, "Uncomp: %d, PCX: %d, G4: %d, PNG: %d", iHeight * iDestPitch * (u8PanelColors[BLEClass.iPanelType] -1), iPCXTotal, iG4Total, iPNGTotal);
        _InfoLabel.stringValue = [NSString stringWithFormat:@"%s", (char *)ucTemp];
        free(pPCXOut);
        free(pG4Out);
        free(pPNGOut);
    }
    return pDest;
} /* DitherImage */

- (void) getRotatedLine:(uint8_t *)s current_x:(int)x destination:(uint8_t *)d
{
    uint8_t uc ,ucSrcMask, ucDestMask;
    int y;
    ucSrcMask = (0x80 >> (x & 7));
    uc = 0xff;
    ucDestMask = 0x80;
    for (y=0; y<iHeight; y++) { // form the bytes of each line
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

// Compress an image using PNG encoding
- (int)compressPNG:(uint8_t *)pSource dest:(uint8_t *)pDest
{
    int x, y, iHeightMult = (u8PanelColors[BLEClass.iPanelType] == 2) ? 1:2;
    uint8_t *s, ucTemp[256];
    int iPitch, rc;
    rc = PNG_openRAM(&png, pDest, OUTBUFFER_SIZE);
    if (u8IsRotated[BLEClass.iPanelType]) { // rotated 90
        rc = PNG_encodeBegin(&png, iHeight, iWidth* iHeightMult, PNG_PIXEL_GRAYSCALE, 1, NULL, 9);
        if (rc == PNG_SUCCESS) {
            for (x=iWidth-1; x>=0 && rc == PNG_SUCCESS; x--) {
                // rotate the image 90
                s = &pSource[x >> 3];
                [self getRotatedLine: s current_x:x destination:ucTemp];
                rc = PNG_addLine(&png, ucTemp, iWidth-1-x);
            } // for each line of image
            if (iHeightMult == 2) { // add second plane
                for (x=iWidth-1; x>=0 && rc == PNG_SUCCESS; x--) {
                    // rotate the image 90
                    s = &pSource[(x >> 3) + (((iWidth+7)/8)*iHeight)];
                    [self getRotatedLine: s current_x:x destination:ucTemp];
                    // invert it for red/yellow plane
                    for (int i=0; i<(iHeight+7)/8; i++) {
                        ucTemp[i] = ~ucTemp[i];
                    }
                    rc = PNG_addLine(&png, ucTemp, (iWidth*2)-1-x);
                } // for each line of image
            }
        } // successful init
    } else { // not rotated
        iPitch = (iWidth+7)>>3;
        rc = PNG_encodeBegin(&png, iWidth, iHeight*iHeightMult, PNG_PIXEL_GRAYSCALE, 1, NULL, 9);
        if (rc == PNG_SUCCESS) {
            for (y=0; y<iHeight*iHeightMult && rc == PNG_SUCCESS; y++) {
                s = &pSource[y*iPitch];
                for (int i=0; i<iPitch; i++) {
                    ucTemp[i] = ~s[i]; // invert the pixels
                }
                rc = PNG_addLine(&png, ucTemp, y);
            } // for each line of image
        } // successful init
    }
    return PNG_close(&png);
} /* compressPNG() */

// Compress an image using CCITT G4 encoding
- (int)compressG4:(uint8_t *)pSource dest:(uint8_t *)pDest
{
    int x, y, iHeightMult = (u8PanelColors[BLEClass.iPanelType] == 2) ? 1:2;
    uint8_t *s, ucTemp[256];
    int iPitch, rc;
    if (u8IsRotated[BLEClass.iPanelType]) { // rotated 90
        rc = G4ENC_init(&g4, iHeight, iWidth*iHeightMult, G4ENC_MSB_FIRST, NULL, pDest, OUTBUFFER_SIZE);
        if (rc == G4ENC_SUCCESS) {
            for (x=iWidth-1; x>=0 && rc == G4ENC_SUCCESS; x--) {
                // rotate the image 90
                s = &pSource[x >> 3];
                [self getRotatedLine: s current_x:x destination:ucTemp];
                rc = G4ENC_addLine(&g4, ucTemp);
            } // for each line of image
            if (iHeightMult == 2) { // add second plane
                for (x=iWidth-1; x>=0 && rc == G4ENC_SUCCESS; x--) {
                    // rotate the image 90
                    s = &pSource[(x >> 3) + (((iWidth+7)/8)*iHeight)];
                    [self getRotatedLine: s current_x:x destination:ucTemp];
                    // invert it for red/yellow plane
                    for (int i=0; i<(iHeight+7)/8; i++) {
                        ucTemp[i] = ~ucTemp[i];
                    }
                    rc = G4ENC_addLine(&g4, ucTemp);
                } // for each line of image
            }
        } // successful init
    } else { // not rotated
        iPitch = (iWidth+7)>>3;
        rc = G4ENC_init(&g4, iWidth, iHeight*iHeightMult, G4ENC_MSB_FIRST, NULL, pDest, OUTBUFFER_SIZE);
        if (rc == G4ENC_SUCCESS) {
            for (y=0; y<iHeight*iHeightMult && rc == G4ENC_SUCCESS; y++) {
                s = &pSource[y*iPitch];
                for (int i=0; i<iPitch; i++) {
                    ucTemp[i] = ~s[i]; // invert the pixels
                }
                rc = G4ENC_addLine(&g4, ucTemp);
            } // for each line of image
        } // successful init
    }
    return G4ENC_getOutSize(&g4);
} /* compressG4() */

// Compress a single PCX line
- (uint8_t) PCXLine:(uint8_t *)s dest:(uint8_t **)ppDest compare:(uint8_t)cCompare line_length:(int)iLen repeats:(int *)pRepeat
{
    int iRepeat = *pRepeat;
    uint8_t *pDest = *ppDest;
    for (int i=0; i<iLen; i++)
    {
        uint8_t c = *s++;
        if (c == cCompare) // another repeat byte?
        {
            iRepeat++;
        }
        else
        {
            while (iRepeat > 63) // store max repeat count
            {
                pDest[0] = 0xff; // max repeat count = 63;
                pDest[1] = cCompare; // byte to repeat
                pDest += 2;
                iRepeat -= 63;
            }
            if (iRepeat > 1) // more than 1 of the same byte, store it
            {
                pDest[0] = (unsigned char)(iRepeat | 0xc0);
                pDest[1] = cCompare;
                pDest += 2;
            }
            else
            {
                if (cCompare >= 0xc0) // same characteristics as repeat code, special case
                {
                    pDest[0] = 0xc1; // store as a repeat of 1
                    pDest[1] = cCompare;
                    pDest += 2;
                }
                else
                    *pDest++ = cCompare; // just store the byte
            }
            iRepeat = 1;   // new repeat count of 1
            cCompare = c; // the current byte becomes the one to repeat
        }
    } // for each byte of image
    *pRepeat = iRepeat;
    *ppDest = pDest;
    return cCompare;
} /* PCXLine() */

// Compress an image using the "PCX" style run length encoding. It encodes runs of repeating bytes as well
// as blocks of non-repeating bytes with a reasonable balance.
- (int)compressPCX:(uint8_t *)pSource dest:(uint8_t *)pDest
{
    int iOutSize;
    int x, y, iPitch, iRepeat;
    uint8_t *s, *pStart, cCompare = 0;
    uint8_t ucTemp[256];
    int iHeightMult = (u8PanelColors[BLEClass.iPanelType] == 2) ? 1:2;
    
    pStart = pDest;
    iRepeat = 0;
    if (u8IsRotated[BLEClass.iPanelType]) { // 90 degrees rotated panel
        s = &pSource[(iWidth-1) >> 3];
        [self getRotatedLine: s current_x:iWidth-1 destination:ucTemp];
        cCompare = ucTemp[0]; // grab first byte for comparison
        iPitch = (iHeight*iHeightMult+7) >> 3;
        for (x=iWidth-1; x>=0; x--)
        {
            s = &pSource[x >> 3];
            [self getRotatedLine: s current_x:x destination:ucTemp];
            cCompare = [self PCXLine:ucTemp dest:&pDest compare:cCompare line_length:iPitch repeats:&iRepeat];
        } // for x
    } else { // not rotated
        cCompare = ~pSource[0];
        iPitch = (iWidth+7) >> 3;
        for (y=0; y<iHeight*iHeightMult; y++)
        {
            s = &pSource[y * iPitch];
            // invert the pixels for e-paper
            for (int i=0; i<iPitch; i++) {
                ucTemp[i] = ~s[i];
            }
            cCompare = [self PCXLine:ucTemp dest:&pDest compare:cCompare line_length:iPitch repeats:&iRepeat];
        } // for x
    }
    // store any remaining repeating bytes
    while (iRepeat > 63) // store max repeat count
    {
        pDest[0] = 0xff; // max repeat count = 63;
        pDest[1] = cCompare; // byte to repeat
        pDest += 2;
        iRepeat -= 63;
    }
    if (iRepeat > 1) // if anything left
    {
        pDest[0] = (unsigned char)(iRepeat | 0xc0);
        pDest[1] = cCompare;
        pDest += 2;
    }
    else
    {
        if (cCompare >= 0xc0) // same characteristics as repeat code, special case
        {
            pDest[0] = 0xc1; // store as a repeat of 1
            pDest[1] = cCompare;
            pDest += 2;
        }
        else
            *pDest++ = cCompare; // just store the byte
    }
    iOutSize = (int)(pDest - pStart);
    return iOutSize;
} /* compressPCX() */

//
// Send image data over BLE
//
- (int)sendData:(uint8_t *)pData type:(uint8_t)u8Type size:(int)iSize
{
    uint8_t ucTemp[512];
    int x, y, iLen, iSent = 0;
    int iBlock, iPayloadSize, iBlockCount;
    float fDelay = 0.0750; // seconds
    
    iPayloadSize = BLEClass.iMTUSize - 1;
    iBlockCount = (iSize + iPayloadSize -1) / iPayloadSize;
    if (u8Type != BLE_CMD_UNCOMPRESSED)
        fDelay = 0.1; // give more time for destination to decode the dat
    
    x = iSize;
    y = 0;
    for (iBlock=0; iBlock<iBlockCount; iBlock++) { // send in relatively large blocks
        iLen = iPayloadSize; // send the maximum amount of data per packet for the most efficient transfer
        if (iLen > x) iLen = x;
        memcpy(&ucTemp[1], &pData[y], iLen); // send up to 200 bytes total per write
        ucTemp[0] = u8Type; // image data type
        if (iBlock == 0)
            ucTemp[0] |= BLE_FIRST_PACKET; // first data block
        if (iBlock == iBlockCount-1) // could be a single block with both flags
            ucTemp[0] |= BLE_LAST_PACKET; // last data block
       [BLEClass writeData:ucTemp withLength:(iLen+1) withResponse:YES];
        [NSThread sleepForTimeInterval: fDelay]; // allow receiver time to process the data
        iSent += iLen+1;
        x -= iLen;
        y += iLen;
    }
    return iSent;
} /* sendData() */

//
// Send the image to the connected ESL
//
- (void)sendImage
{
    uint8_t *s, *d;
    int x, y, iSent, iTotal, iG4Total, iPCXTotal;
    uint8_t *pG4Out, *pPCXOut, *pOut;
    int iHeightMult = (u8PanelColors[BLEClass.iPanelType] == 2) ? 1:2;
    int iPitch;
    if (pDithered == NULL) return; // no image to send
    if (u8IsRotated[BLEClass.iPanelType]) {
        iPitch = ((iHeight*iHeightMult)+7)/8;
        iTotal = iPitch * iWidth;
    } else {
        iPitch = (iWidth+7)/8;
        iTotal = iPitch * iHeight * iHeightMult;
    }
    pPCXOut = malloc(OUTBUFFER_SIZE); // max reasonable size
    pG4Out = malloc(OUTBUFFER_SIZE);
    NSLog(@"Uncompressed size = %d", iTotal);
    // Test if it can be compressed
    iPCXTotal = [self compressPCX:pDithered dest:pPCXOut];
    NSLog(@"PCX compressed size = %d", iPCXTotal);
    iG4Total = [self compressG4:pDithered dest:pG4Out];
    NSLog(@"G4 compressed size = %d", iG4Total);
    // Now send it to the ESL
    if (iG4Total < iTotal && iG4Total < iPCXTotal) { // G4 beat uncompressed and PCX
        iSent = [self sendData:pG4Out type:BLE_CMD_G4 size:iG4Total];
    } else if (iPCXTotal < iTotal) { // PCX beat uncompressed and G4
        iSent = [self sendData:pPCXOut type:BLE_CMD_PCX size:iPCXTotal];
    } else { // send uncompressed
        // transmit the uncompressed data in the destination EPD format to be written directly on arrival
        // first prepare the uncompressed data in a single block
        iSent = 0;
        pOut = malloc(iTotal);
        if (u8IsRotated[BLEClass.iPanelType]) {
            for (x=iWidth-1; x>=0; x--) {
                s = &pDithered[x>>3];
                [self getRotatedLine: s current_x:x destination: &pOut[iSent]];
                iSent += iPitch;
            } // for x
        } else { // not rotated
            d = pOut;
            for (y=0; y<iHeight*iHeightMult; y++) {
                s = &pDithered[y*iPitch];
                for (int i=0; i<iPitch; i++) {
                    *d++ = ~s[i]; // invert for e-paper
                }
            } // for y
        } // not rotated
        iSent = [self sendData:pOut type:BLE_CMD_UNCOMPRESSED size:iTotal];
        free(pOut);
    } // uncompressed
    NSLog(@"Total bytes sent over BLE = %d", iSent);
    // Free local compressed data buffers
    free(pPCXOut);
    free(pG4Out);
} /* sendImage */

- (void)statusChanged:(NSNotification *) notification
{
    if ([BLEClass isConnected]) {
        _StatusLabel.stringValue = [NSString stringWithFormat:@"Connected to: %@, %d x %d, %s", [BLEClass getName], iPanelWidths[BLEClass.iPanelType], iPanelHeights[BLEClass.iPanelType], (u8PanelColors[BLEClass.iPanelType] == 2) ? "BW": "BWR"];
        _ConnectButton.title = @"Disconnect";
    } else {
        _StatusLabel.stringValue = @"Disconnected";
        _ConnectButton.title = @"Connect";
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
