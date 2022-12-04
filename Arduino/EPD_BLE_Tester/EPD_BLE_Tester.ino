#include <OneBitDisplay.h>
#include <ArduinoBLE.h>
#include <TIFF_G4.h>

static TIFFG4 g4;
static BLEDevice peripheral;
static uint8_t ucBuffer[512]; // receive buffer for this characteristic
static uint8_t ucCompressed[16384];
static uint8_t ucImage[(400*300)/4];
static int iOffset, iHeightMult;
BLEService tiffService("13187b10-eba9-a3ba-044e-83d3217d9a38"); // BLE TIFF Service
BLECharacteristic tiffCharacteristic("4b646063-6264-f3a7-8941-e65356ea82fe", BLERead | BLEWrite | BLEWriteWithoutResponse, 512);

// command bytes
enum {
  EPD_ERASE=0,
  EPD_DISPLAY,
  EPD_SETOFFSET,
  EPD_DATA,
  EPD_COMPRESSED_DATA,
  EPD_COUNT
};

ONE_BIT_DISPLAY oled, epd;
#define CS_PIN 10
#define DC_PIN 16
#define RESET_PIN 14
#define BUSY_PIN 15

#define BUTTON1 2
#define BUTTON2 3
int iPanel = 0;
int iMode = 0; // 0=EPD test, 1=BLE test
#define PANEL_COUNT 22

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
  if (ucLastCommand == EPD_COMPRESSED_DATA) { // it's CCITT G4
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

void epdBegin()
{
  epd.setSPIPins(CS_PIN, -1, -1, DC_PIN, RESET_PIN, BUSY_PIN);
  epd.SPIbegin(iPanel + EPD42_400x300, 8000000); // initalize library for this panel
  epd.setBuffer(ucImage);
} /* epdBegin() */

void BLETest(void)
{
uint8_t ucLastCommand = EPD_COUNT;
  epdBegin();
  oled.fillScreen(OBD_WHITE);
  oled.setFont(FONT_12x16);
  if (!BLE.begin()) {
    oled.println("BLE failed");
    while (1);
  }
  oled.println("Waiting...");
   BLE.setDeviceName("EPD_Tester");
   BLE.setLocalName("EPD_Tester");
   BLE.setManufacturerData((const uint8_t *)&iPanel, 4);
   BLE.setAdvertisedService(tiffService);
  // add the characteristic to the service
   tiffService.addCharacteristic(tiffCharacteristic);
   BLE.addService(tiffService);
   // start advertising
   BLE.advertise();
   while (1) {
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
        switch (ucBuffer[0]) { // first byte is the command
          case EPD_ERASE: // erase the display
            memset(ucCompressed, ucBuffer[1], sizeof(ucCompressed));
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
            iOffset = ucBuffer[1] | (ucBuffer[2] << 8);
            break;
          case EPD_DATA: // image data
          case EPD_COMPRESSED_DATA:
            memcpy(&ucCompressed[iOffset], &ucBuffer[1], iLen-1);
            iOffset += (iLen-1);
            break;
        }
        ucLastCommand = ucBuffer[0];
      }
    } // while connected
    oled.fillScreen(OBD_WHITE);
    BLE.end();
    return; // disconnected, return to main menu
  } // if central
  } // while (1)
} /* BLEStart() */

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
    oled.println(" BLE test");
  else
    oled.println(" Clear EPD");
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
         if (iMode > 2) iMode = 0;
         ShowInfo();
       } else {
         // start the operation
         if (iMode == 0) { // EPD test
            EPDTest();
         } else if (iMode == 1) {
            BLETest();
         } else {
            EPDClear();
         }
         ShowInfo(); // repaint the main menu info
       }
    }
    iOldButt1 = iButt1;
  }
} /* loop() */
