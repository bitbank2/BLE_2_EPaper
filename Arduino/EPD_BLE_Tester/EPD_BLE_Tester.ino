#if defined(ARDUINO_ARDUINO_NANO33BLE) || (defined(HAL_ESP32_HAL_H_) && !defined(ARDUINO_FEATHERS2))
#define HAS_BLE
#define MODE_COUNT 3
#else
#define MODE_COUNT 2
#endif

#include <OneBitDisplay.h>
#ifdef HAL_ESP32_HAL_H_
// Allow e-paper panel clearing/testing to work without BLE
#ifndef ARDUINO_FEATHERS2
#include <BLEDevice.h>
#include <BLEServer.h>
#endif
#elif !defined(HAL_ESP32_HAL_H_)
#include <ArduinoBLE.h>
#endif
#include <TIFF_G4.h>
//static uint8_t ucImage[(640*480)/4];
static TIFFG4 g4;
//static BLEDevice peripheral;
#ifdef HAS_BLE
static bool bActive = false;
static int iHead, iTail, epd_buffer_size;
static uint8_t epd_temp[512*2]; // receive buffer for this characteristic (max 2 * MTU)
static uint8_t ucBuffer[512]; // receive buffer
//static uint8_t ucCompressed[65536];
//static uint8_t ucLastCommand;
static int iTotal, iWidthMult, iHeightMult;
#ifdef HAL_ESP32_HAL_H_
static BLEUUID tiffService("13187b10-eba9-a3ba-044e-83d3217d9a38");
static BLEUUID tiffCharacteristic ("4b646063-6264-f3a7-8941-e65356ea82fe");
BLEServer *pServer;
BLEService *pService;
BLECharacteristic *pCharacteristic;
BLEAdvertising *pAdvertising;
BLEAdvertisementData advertisementData;
bool deviceConnected = false;
#else
BLEService tiffService("13187b10-eba9-a3ba-044e-83d3217d9a38"); // BLE TIFF Service
BLECharacteristic tiffCharacteristic("4b646063-6264-f3a7-8941-e65356ea82fe", BLERead | BLEWrite | BLEWriteWithoutResponse, 512);
#endif
// command bytes
enum {
  EPD_ERASE=0,
  EPD_UNCOMPRESSED,
  EPD_G4,
  EPD_PCX,
  EPD_PNG,
  EPD_GFX_CMDS,
  EPD_COUNT
};
#define EPD_CMD_MASK 0x3f
#define EPD_FIRST_PACKET 0x40
#define EPD_LAST_PACKET 0x80
#endif // BLE support

ONE_BIT_DISPLAY oled, epd;

#ifdef HAL_ESP32_HAL_H_
// These values are for the Laska_Kit ESPInk board
// with a pushbutton and pull down resistor attached
// to the RX0/TX0 signals exposed on the 8-pin female programming header
#if defined (ARDUINO_FEATHERS3) || defined(ARDUINO_FEATHERS2)
#define CS_PIN 5
#define DC_PIN 6
#define RESET_PIN 12
#define BUSY_PIN 14
#define CLK_PIN 36
#define MOSI_PIN 35
#define POWER_PIN 39
#else // must be Laska_Kit ESPInk
#define CS_PIN 5
#define DC_PIN 17
#define RESET_PIN 16
#define BUSY_PIN 4
#define CLK_PIN 18
#define MOSI_PIN 23
#define POWER_PIN 2
#endif

#define BUTTON1 1
#define BUTTON2 3
#else
// These pin assignments are for a custom e-paper
// adapter add-on with 2 push buttons
// for the Arduino Nano 33 BLE
#define CS_PIN 10
#define DC_PIN 16
#define RESET_PIN 14
#define BUSY_PIN 15
#define CLK_PIN -1
#define MOSI_PIN -1
#define POWER_PIN -1

#define BUTTON1 2
#define BUTTON2 3
#endif
int iPanel = 0;
int iMode = 0; // 0=EPD test, 1=BLE test
#define PANEL_COUNT 22

// Friendly name for the OneBitDisplay library e-paper panel types
// The first 2 digits signify the panel size (e.g. 42 = 4.2")
// A letter "R" after the size indicates a Black/White/RED panel
// The X*Y indicate the native pixel resolution
const char *szPanelNames[] = {
  "EPD42_400x300", // WFT0420CZ15
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
// List of supported colors for each panel type
// 2 = Black/White, 3 = Black/White/Red
const uint8_t u8PanelColors[] = {2,2,2,3,2,3,3,2,3,3,2,2,2,2,3,2,2,2,2,2,3,3};
const uint8_t u8IsRotated[] = {0,1,1,1,1,0,0,1,1,1,1,1,1,1,0,0,0,1,1,1,1,0,0};

#ifdef HAS_BLE
#define BLE_TIMEOUT 1000
void  epd_decode_pcx(uint8_t *pBuf, int *pTail, int *pHead, uint8_t u8Cmd)
{
uint8_t uc, *s, *pEnd;
int i;

    u8Cmd &= EPD_LAST_PACKET;
    s = &pBuf[*pTail];
    pEnd = &pBuf[*pHead];
    if (!u8Cmd) { // if not the last packet, we may be missing a repeating byte pair
       pEnd -= 2;
    }
    while (s < pEnd) {
        uc = *s++;
        if (uc >= 0xc0) { // repeating byte
          uc &= 0x3f; // repeat count
          for (i=0; i<uc; i++) {
              epd.pushPixels(&s[0], 1);
              iTotal++;
              if (iTotal == epd_buffer_size && u8PanelColors[iPanel] == 3) {
               //   EPD_WriteCmd(0x13); // start of next memory plane
              }
          } // for i
          s++;
        } else {
          epd.pushPixels(&uc, 1); // just write it
          iTotal++;
          if (iTotal == epd_buffer_size && u8PanelColors[iPanel] == 3) {
           //   EPD_WriteCmd(0x13); // start of next memory plane
          }
        }
    } // while decoding
    *pTail = (int)(s - pBuf); // update tail pointer
} /* epd_decode_pcx() */

// Receives BLE data writes
void myDataWrite(uint8_t *pData, int iLen) {
        if (pData[0] & EPD_FIRST_PACKET) {
          int cx, cy;
          bActive = true; // new image reception started
          if (epd.getRotation() == 0) {
            cx = epd.width();
            cy = epd.height();
          } else {
            cx = epd.height();
            cy = epd.width();
          }
          epd.setTextColor(OBD_BLACK, OBD_WHITE); // first EPD plane to write
          epd.setPosition(0,0,cx, cy); // get ready to write data
          epd_buffer_size = epd.height() * (epd.width() + 7)/8;
          //oled.println("First packet");
          iHead = iTail = iTotal = 0;
        }
        switch (pData[0] & EPD_CMD_MASK) { // first byte is the command
          case EPD_ERASE: // erase the display
            epd.fillScreen(OBD_WHITE);
            epd.display();
            break;
          case EPD_UNCOMPRESSED: // image data
             epd.pushPixels(&pData[1], iLen-1); // write it straight into the e-paper panel RAM
             break;
          case EPD_G4:
              if (pData[0] & EPD_FIRST_PACKET) {
                  g4.decodeIncBegin(epd.width(), epd.height()*(u8PanelColors[iPanel]-1), BITDIR_MSB_FIRST, TIFFDraw);
              }
              g4.addData(&pData[1], iLen-1);
              g4.decodeInc(!(pData[0] & EPD_LAST_PACKET)); // try to decode a group of lines with the data available
             break;
          case EPD_PCX:
              if (iHead != iTail) { // move down existing data
                  memmove(epd_temp, &epd_temp[iTail], (iHead-iTail));
                  iHead -= iTail;
                  iTail = 0;
              } else {
                  iHead = iTail = 0; // if we used all data before, reset to 0
              }
              memcpy(&epd_temp[iHead], &pData[1], iLen-1);
              iHead += (iLen-1);
              epd_decode_pcx(epd_temp, &iTail, &iHead, pData[0]);
            break;
        } /* switch on command */
        if (pData[0] & EPD_LAST_PACKET) {
             epd.display();
             bActive = false;
        }
} /* myDataWrite() */

#ifdef HAL_ESP32_HAL_H_

class MyServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
  };
  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
  }
};
class CharacteristicCallbacks: public BLECharacteristicCallbacks {
     void onWrite(BLECharacteristic *characteristic) {
        int len;
        uint8_t *p;

        std::string rxValue = characteristic->getValue();
        len = rxValue.length();
        if (len > 0) {
            p = (uint8_t *)rxValue.c_str();
         // normal packet, pass it along
         myDataWrite(p, len);
        } // if (len > 0)
     }/* onWrite() */

//     void onRead (BLECharacteristic *characteristic) {
//        characteristic->setValue(u16Buttons);
//     } /* onRead() */
};
#endif // ESP32

void TIFFDraw(TIFFDRAW *pDraw)
{
  int iPitch = ((pDraw->iWidth+7)>>3);
  if (pDraw->y == epd.height() && u8PanelColors[iPanel] == 3) {
        epd.setTextColor(OBD_RED, OBD_WHITE); // activate the second memory plane on BWR/BWY EPDs
        epd.setPosition(0,0, epd.width(), epd.height());
  }
// write the data straight into the EPD display buffer
// since it has been formatted+oriented that way at the source
  epd.pushPixels(pDraw->pPixels, iPitch);
} /* TIFFDraw() */

#ifdef NOT_USED
void UnpackBuffer(uint8_t ucLastCommand)
{
int iWidth = epd.width();
int iHeight = epd.height();
uint8_t  uc, *s, *d, ucMask, ucSrcMask;
  if (ucLastCommand == EPD_G4) { // it's CCITT G4
    if (g4.openRAW(iWidth*iWidthMult, iHeight*iHeightMult, BITDIR_MSB_FIRST, ucCompressed, iOffset, TIFFDraw))
    {
      g4.setDrawParameters(1.0f, TIFF_PIXEL_1BPP, 0, 0, iWidth*iWidthMult, iHeight*iHeightMult, NULL);
      oled.print("G4 ");
      oled.print(iWidth*iWidthMult, DEC);
      oled.print("*");
      oled.println(iHeight*iHeightMult, DEC);
      if (g4.decode(0,0) != TIFF_SUCCESS)
         oled.println("decode error");
      else
         oled.println("decode success");
      g4.close();
      memcpy(ucCompressed, ucImage, (((iWidth*iWidthMult)+7)>> 3) * iHeight*iHeightMult); // copy back to original buffer to be rotated
    }
  } else if (ucLastCommand == EPD_PCX) { // PCX run-length compressed
    uint8_t *pEnd = ucCompressed + iOffset; // end of data
    s = ucCompressed;
      oled.println("PCX");
      d = ucImage;
      while (s < pEnd) {
        uc = *s++;
        if (uc >= 0xc0) { // repeating byte
          memset(d, s[0], uc & 0x3f); // up to 63 of the same byte repeated
          s++;
          d += (uc & 0x3f);
        } else {
          *d++ = uc; // just store it
        }
      } // while decoding PCX
      memcpy(ucCompressed, ucImage, (((iWidth*iHeightMult)+7)>> 3) * iHeight); // copy back to original buffer to be rotated
  } else {
      oled.println("Uncompressed");
  }
  s = ucCompressed;
  ucMask = 1;
  for (int y=0; y<iHeight*iHeightMult; y++) {
    d = &ucImage[(y>>3) * iWidth];
    ucSrcMask = 0; // force realign to start of next whole byte at the start of each line
  for (int x=0; x<iWidth*iWidthMult; x++) {
      if (x == iWidth) { // adjust for second memory plane
         d += (iWidth * ((iHeight+7)>>3));
         d -= iWidth;
      }
       if (ucSrcMask == 0) {
         uc = *s++;
         ucSrcMask = 0x80;
       }
       if (uc & ucSrcMask)
         d[x] &= ~ucMask;
      else
        d[x] |= ucMask;
      ucSrcMask >>= 1;
    } // for x
  
    ucMask <<= 1;
    if (ucMask == 0) {
      ucMask = 1;
      d += iWidth;
    }
  } // for y
} /* UnpackBuffer() */
#endif // NOT_USED

void BLETest(void)
{
  epdBegin();
  oled.fillScreen(OBD_WHITE);
  oled.setFont(FONT_12x16);
#ifdef HAL_ESP32_HAL_H_
    BLEDevice::init("EPD_ESP32");
#else
  if (!BLE.begin()) {
    oled.println("BLE failed");
    while (1);
  }
#endif
  oled.println("Waiting...");
#ifdef HAL_ESP32_HAL_H_
  BLEDevice::setMTU(256); // allow bigger MTU size
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  pService = pServer->createService(tiffService);
 
  pCharacteristic = pService->createCharacteristic(
                                         tiffCharacteristic,
                                         BLECharacteristic::PROPERTY_READ |
                                         BLECharacteristic::PROPERTY_WRITE |
                                         BLECharacteristic::PROPERTY_WRITE_NR
                                       );
  pCharacteristic->setCallbacks(new CharacteristicCallbacks());
//  pCharacteristic->setValue(u16Buttons);
  pService->start();
  pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(tiffService);
//  pAdvertising->setScanResponse(true);
//  pAdvertising->setMinPreferred(0x06);  // functions that help with iPhone connections issue
//  pAdvertising->setMinPreferred(0x12);
  advertisementData.setShortName("EPD_ESP32");
  advertisementData.setManufacturerData((char *)&iPanel);
  pAdvertising->setAdvertisementData(advertisementData);
 
  BLEDevice::startAdvertising();
#else
   BLE.setDeviceName("EPD_Nano33");
   BLE.setLocalName("EPD_Nano33");
   BLE.setManufacturerData((const uint8_t *)&iPanel, 4);
   BLE.setAdvertisedService(tiffService);
  // add the characteristic to the service
   tiffService.addCharacteristic(tiffCharacteristic);
   BLE.addService(tiffService);
   // start advertising
   BLE.advertise();
#endif

   while (1) {
  #ifdef HAL_ESP32_HAL_H_
  // wait for a connection
    while (!deviceConnected) {
      delay(20);
    }
    oled.setCursor(0,0);
    oled.println("Connected!");
    while (deviceConnected) {
      
    }
    oled.fillScreen(OBD_WHITE);
    BLEDevice::stopAdvertising();
    BLEDevice::deinit();
    return; // disconnected, return to main menu
  #else
  // listen for BLE peripherals to connect:
  BLEDevice central = BLE.central();

  // if a central is connected to peripheral:
  if (central) {
    long lTime;
    oled.setCursor(0,0);
    oled.println("Connected!");
    // print the central's MAC address:
    oled.setFont(FONT_6x8);
    oled.println(central.address());
    oled.setFont(FONT_8x8);
    while (central.connected()) {
      // if the remote device wrote to the characteristic,
      // use the value to control the LED:
      if (tiffCharacteristic.written()) {
        int iLen = tiffCharacteristic.valueLength();
        tiffCharacteristic.readValue(ucBuffer, iLen);
        myDataWrite(ucBuffer, iLen);
        lTime = millis(); // time of last write
      } else {
        if (bActive && millis() - lTime > BLE_TIMEOUT) {
          // something went wrong; the data was missed or stopped
          oled.println("Timeout error!");
          bActive = false;
        }
      }
    } // while connected
    oled.fillScreen(OBD_WHITE);
    BLE.end();
    return; // disconnected, return to main menu
  } // if central
#endif // Nano33

  } // while (1)
} /* BLETest() */

#endif // HAS_BLE

void epdBegin()
{
  if (POWER_PIN != -1) {
    pinMode(POWER_PIN, OUTPUT);
    digitalWrite(POWER_PIN, HIGH);
    delay(100); // allow time to settle
  }
  epd.setSPIPins(CS_PIN, MOSI_PIN, CLK_PIN, DC_PIN, RESET_PIN, BUSY_PIN);
  epd.SPIbegin(iPanel + EPD42_400x300, 8000000); // initalize library for this panel
//  epd.allocBuffer();
 // epd.setBuffer(ucImage);
} /* epdBegin() */

void ShowInfo(void)
{
  oled.fillScreen(OBD_WHITE);
  oled.setCursor(0, 0);
  oled.setFont(FONT_8x8);
  oled.println("EPD Panel Tester");
  oled.setCursor(0, 16);
  oled.print(szPanelNames[iPanel]);
  oled.print("      "); // erase old name if longer
  oled.setCursor(0, 40);
  oled.setFont(FONT_12x16);
  if (iMode == 0)
    oled.println(" EPD test");
  else if (iMode == 1)
    oled.println(" Clear EPD");
  else
    oled.println(" BLE test");
  oled.setFont(FONT_6x8);
  oled.print("(press&hold to exec)");
} /* ShowInfo() */

void EPDTest(void)
{
  oled.fillScreen(OBD_WHITE);
  oled.setFont(FONT_12x16);
  oled.println("EPD Test");
  oled.println("Starting...");
  epdBegin();
  if (epd.width() < epd.height() || !epd.getBuffer()) {
       epd.setRotation(90);
  }
  
  epd.fillScreen(OBD_WHITE);
  epd.setFont(FONT_12x16);
  epd.setTextColor(OBD_BLACK, OBD_WHITE);
  epd.println("EPD Test");
  epd.print("Panel size: ");
  epd.print(epd.width(), DEC);
  epd.print(" x ");
  epd.println(epd.height(), DEC);
  if (epd.getBuffer())
     epd.println("With backbuffer");
  else
     epd.println("No backbuffer (0 RAM)");
  if (u8PanelColors[iPanel] == 2)
    epd.println("Two colors (Black/White)");
  else {
    epd.setTextColor(OBD_RED, OBD_WHITE);
    epd.println("Three colors (B/W/R)");
  }
  epd.display();
} /* EPDTest() */

void EPDClear(void)
{
  oled.fillScreen(OBD_WHITE);
  oled.setFont(FONT_12x16);
  oled.println("Clear EPD");
  oled.println("Starting...");
  epdBegin();
  if (epd.width() < epd.height() || !epd.getBuffer()) {
       epd.setRotation(90);
  }
  epd.fillScreen(OBD_WHITE);
  epd.display(); // display the white buffer and return
} /* EPDClear() */

void setup() {
  oled.I2Cbegin();
  oled.fillScreen(OBD_WHITE);
  pinMode(BUTTON1, INPUT_PULLUP);
  pinMode(BUTTON2, INPUT_PULLUP);
} /* setup() */

void loop() {
  int iOldButt1 = 1;
  int iButt1, iButt2;
  long iTime;

  ShowInfo();  
  while (1) {
    iButt1 = digitalRead(BUTTON1);
    iButt2 = digitalRead(BUTTON2);
    delay(25); // allow time for debounce
    // Panel selection button
    if (iButt1 == 0 && iOldButt1 == 1) {
      iPanel++;
      if (iPanel >= PANEL_COUNT) iPanel = 0;
      ShowInfo();
    }
    // Mode selection/start button
    if (iButt2 == 0) {
       iTime = millis();
       while (digitalRead(BUTTON2) == 0 && (millis() - iTime < 1000)) { // measure how long button is held
         delay(10);
       }
       iTime = millis() - iTime;
       if (iTime < 1000) {
         iMode++;
         if (iMode >= MODE_COUNT) iMode = 0;
         ShowInfo();
       } else {
         // start the operation
         if (iMode == 0) { // EPD test
            EPDTest();
         } else if (iMode == 1) {
            EPDClear();
         } else {
#ifdef HAS_BLE
            BLETest();
#endif
         }
         ShowInfo(); // repaint the main menu info
       }
    }
    iOldButt1 = iButt1;
  }
} /* loop() */
