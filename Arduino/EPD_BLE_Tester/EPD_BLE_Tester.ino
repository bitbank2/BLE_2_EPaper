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
#else
#include <ArduinoBLE.h>
#endif
#include <TIFF_G4.h>
static uint8_t ucImage[(400*300)/4];
static TIFFG4 g4;
//static BLEDevice peripheral;
#ifdef HAS_BLE
static uint8_t ucBuffer[512]; // receive buffer for this characteristic
static uint8_t ucCompressed[16384];
static uint8_t ucLastCommand;
static int iOffset, iHeightMult;
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
  EPD_DISPLAY,
  EPD_SETOFFSET,
  EPD_DATA,
  EPD_G4_DATA,
  EPD_PCX_DATA,
  EPD_PNG_DATA,
  EPD_COUNT
};
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

#ifdef HAS_BLE

// Receives BLE data writes
void myDataWrite(uint8_t *pData, int iLen) {
        switch (pData[0]) { // first byte is the command
          case EPD_ERASE: // erase the display
            memset(ucCompressed, pData[1], sizeof(ucCompressed));
            break;
          case EPD_DISPLAY: // display the image
            if (iOffset > 0) {
              oled.print("Data size: ");
              oled.println(iOffset, DEC);
            }
            iHeightMult = (u8PanelColors[iPanel] == 2) ? 1:2; // double the height for BWR images
            UnpackBuffer(ucLastCommand); // correct the pixel direction or decode the compressed data
            epd.display();
            break;
          case EPD_SETOFFSET: // set output offset
            iOffset = pData[1] | (pData[2] << 8);
            break;
          case EPD_DATA: // image data
          case EPD_G4_DATA:
          case EPD_PCX_DATA:
          case EPD_PNG_DATA:
            memcpy(&ucCompressed[iOffset], &pData[1], iLen-1);
            iOffset += (iLen-1);
            break;
        }
        ucLastCommand = pData[0];
}

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
  memcpy(&ucImage[pDraw->y * iPitch], pDraw->pPixels, iPitch);
} /* TIFFDraw() */

void UnpackBuffer(uint8_t ucLastCommand)
{
int iWidth = epd.width();
int iHeight = epd.height();
uint8_t  uc, *s, *d, ucMask, ucSrcMask;
  if (ucLastCommand == EPD_G4_DATA) { // it's CCITT G4
    if (g4.openRAW(iWidth*iHeightMult, iHeight, BITDIR_MSB_FIRST, ucCompressed, iOffset, TIFFDraw))
    {
      g4.setDrawParameters(1.0f, TIFF_PIXEL_1BPP, 0, 0, iWidth*iHeightMult, iHeight, NULL);
      oled.println("CCITT G4");
      if (g4.decode(0,0) != TIFF_SUCCESS)
         oled.println("decode error");
      else
         oled.println("decode success");
      g4.close();
      memcpy(ucCompressed, ucImage, (((iWidth*iHeightMult)+7)>> 3) * iHeight); // copy back to original buffer to be rotated
    }
  } else if (ucLastCommand == EPD_PCX_DATA) { // PCX run-length compressed
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
  for (int y=0; y<iHeight; y++) {
    d = &ucImage[(y>>3) * iWidth];
    ucSrcMask = 0; // force realign to start of next whole byte at the start of each line
  for (int x=0; x<iWidth*iHeightMult; x++) {
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

void BLETest(void)
{
  ucLastCommand = EPD_COUNT;
  epdBegin();
  oled.fillScreen(OBD_WHITE);
  oled.setFont(FONT_12x16);
#ifdef HAL_ESP32_HAL_H_
    BLEDevice::init("EPD_Tester");
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
  advertisementData.setShortName("EPD_Tester");
  advertisementData.setManufacturerData((char *)&iPanel);
  pAdvertising->setAdvertisementData(advertisementData);
 
  BLEDevice::startAdvertising();
#else
   BLE.setDeviceName("EPD_Tester");
   BLE.setLocalName("EPD_Tester");
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
  epd.setBuffer(ucImage);
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
  if (epd.width() < epd.height())
     epd.setRotation(90);
  epd.fillScreen(OBD_WHITE);
  epd.setFont(FONT_12x16);
  epd.setTextColor(OBD_BLACK, OBD_WHITE);
  epd.println("Black text");
  epd.setTextColor(OBD_RED, OBD_WHITE);
  epd.println("Red text");
  epd.display();
} /* EPDTest() */

void EPDClear(void)
{
  oled.fillScreen(OBD_WHITE);
  oled.setFont(FONT_12x16);
  oled.println("Clear EPD");
  oled.println("Starting...");
  epdBegin();
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
