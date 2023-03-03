package com.example.eslimagetransfer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.eslimagetransfer.databinding.FragmentSecondBinding;
import com.example.eslimagetransfer.EPD_Image_Prep;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.UUID;

public class SecondFragment extends Fragment {

    private static FragmentSecondBinding binding;
    private Context localContext;
    private static Handler mHandler;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mCharacteristic = null;
    private volatile boolean bConnected = false;
    private static boolean bBitmapLoaded = false;
    private static boolean bDither = false;
    private static boolean bSendImage = true; // false = clear, true = image
    private static final UUID ESL_SERVICE_UUID = UUID.fromString("13187b10-eba9-a3ba-044e-83d3217d9a38");
    private static final UUID ESL_CHARACTERISTIC_UUID = UUID.fromString("4b646063-6264-f3a7-8941-e65356ea82fe");
    // Request code for selecting an image file.
    private static final int PICK_IMAGE_FILE = 2;
    private static Bitmap theBitmap = null;
    private static Bitmap originalBitmap = null;
    private static int iMTUSize = 23; // default size
    private static Bitmap bmFont24, bmFont16;
    // State variables used for transmitting the image
    private static byte comp_type; // data compression type
    private static byte[] outbytes;
    private static int write_offset, write_len, write_size;
    private static int iMaxPayload;

    private static final int[] iPanelPlanes = {1,1,1,2,2,1,2,2,1,2,2,2,1,1,1,1,2,2,1,1,1,1,2,2,1,1,2,2,2};
    private static final int[] iIsRotated = {0,1,1,1,1,1,0,0,1,1,1,1,1,1,1,0,0,0,0,1,1,1,1,1,1,1,0,0,1};
    private static final int[] iPanelWidths = {400, 296, 296, 296, 296, 296, 400, 400, 212, 212, 250, 212, 212, 250, 250, 152, 152, 152, 200, 264, 264, 296, 296, 416, 416, 792, 600, 640, 384};
    private static final int[] iPanelHeights = {300, 128, 128, 128, 128, 128, 300, 300, 104, 104, 122, 104, 104, 122, 122, 152, 152, 152, 200, 176, 176, 152, 168, 240, 240, 272, 448, 384, 184};
    private static final String[] szPanelNames = {  "EPD42_400x300", // WFT0420CZ15
            "EPD29_128x296",
            "EPD29B_128x296",
            "EPD29R_128x296",
            "EPD29Y_128x296",
            "EPD293_128x296",
            "EPD42R_400x300",
            "EPD42R2_400x300",
            "EPD213B_104x212",
            "EPD213R_104x212",
            "EPD213R2_122x250",
            "EPD213R_104x212_d",
            "EPD213_104x212",
            "EPD213_122x250", // waveshare
            "EPD213B_122x250", // GDEY0213B74
            "EPD154_152x152", // GDEW0154M10
            "EPD154R_152x152",
            "EPD154Y_152x152",
            "EPD154_200x200", // waveshare
            "EPD27_176x264", // waveshare
            "EPD27b_176x264", // GDEY027T91
            "EPD266_152x296", // GDEY0266T90
            "EPD31R_168x296", // DEPG0310RW
            "EPD37Y_240x416", // DEPG0370YN
            "EPD37_240x416", // GDEY037T03
            "EPD579_792x272", // GDEY0579T93
            "EPD583R_600x448",
            "EPD74R_640x384",
            "EPD35Y_184x384", // Hanshow Nebular black/white/yellow (#22)
    };

    // Commands/data types to send BLE2Epaper device
    public static final int EPD_ERASE = 0;
    public static final int EPD_UNCOMPRESSED = 1;
    public static final int EPD_G4 = 2;
    public static final int EPD_PCX = 3;
    public static final int EPD_PNG = 4;
    public static final int EPD_PB = 5;
    public static final int EPD_LZW = 6;
    public static final int EPD_GFX_CMDS = 7;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentSecondBinding.inflate(inflater, container, false);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inDither = false;
        bmFont24 = BitmapFactory.decodeResource(getResources(), R.drawable.arial_16x24_1bpp, options);
        bmFont16 = BitmapFactory.decodeResource(getResources(), R.drawable.font12x16, options);
        return binding.getRoot();

    }

    private static void DrawBMFont(Bitmap bm, int x, int y, String s, Bitmap bmFont) {
        Canvas canvas = new Canvas(bm);
        Rect src, dst;
        Paint paint = new Paint();
        int cx, cy;
        cx = bmFont.getWidth() / 16; // bitmap font sources are 16 cols x 6 rows of characters
        cy = bmFont.getHeight()/6;
        for (int i=0; i<s.length() && x < bm.getWidth(); i++) {
            int c = (int)s.charAt(i) - 32;
            int sx = (c & 15) * cx; // source x of the character
            int sy = (c / 16) * cy; // source y of the character
            src = new Rect(sx, sy, sx+cx, sy+cy);
            dst = new Rect(x, y, x+cx, y + cy);
            canvas.drawBitmap(bmFont, src, dst, paint);
            x += cx;
        }
    } /* DrawBMFont() */

    private static void UpdateBitmap()
    {

      //  ((MainActivity)localContext).runOnUiThread(new Runnable() {
      //      public void run()
        if (bBitmapLoaded) {
            theBitmap = EPD_Image_Prep.PrepareBitmap(originalBitmap, (iPanelPlanes[FirstFragment.iPanelType] == 1) ? EPD_Image_Prep.EPDTYPE.EPD_BW : EPD_Image_Prep.EPDTYPE.EPD_BWR, bDither, iPanelWidths[FirstFragment.iPanelType], iPanelHeights[FirstFragment.iPanelType]);
            binding.imageviewSecond.setImageBitmap(theBitmap);
                byte[] uncompressed = EPD_Image_Prep.PrepareImageData(theBitmap, (iPanelPlanes[FirstFragment.iPanelType] == 1) ? EPD_Image_Prep.EPDTYPE.EPD_BW : EPD_Image_Prep.EPDTYPE.EPD_BWR, (iIsRotated[FirstFragment.iPanelType] == 1) ? 90 : 0, false);
                byte[] PCX = EPD_Image_Prep.CompressPCX(uncompressed);
                byte[] PB = EPD_Image_Prep.CompressPackBits(uncompressed);
                byte[] G4 = EPD_Image_Prep.CompressG4(uncompressed, theBitmap.getWidth(), theBitmap.getHeight(), (iPanelPlanes[FirstFragment.iPanelType] == 1) ? EPD_Image_Prep.EPDTYPE.EPD_BW : EPD_Image_Prep.EPDTYPE.EPD_BWR, (iIsRotated[FirstFragment.iPanelType] == 1) ? 90 : 0);
                int iG4 = 0, iPCX = 0, iPB = 0, iUncomp;
                iUncomp = uncompressed.length;
                Log.i("ESLImageTransfer", String.format(Locale.ENGLISH, "Uncompressed size = %d", uncompressed.length));
                if (G4 != null) {
                    iG4 = G4.length;
                    Log.i("ESLImageTransfer", String.format(Locale.ENGLISH, "G4 size = %d", G4.length));
                }
                if (PCX != null) {
                    iPCX = PCX.length;
                    Log.i("ESLImageTransfer", String.format(Locale.ENGLISH, "PCX size = %d", PCX.length));
                }
                if (PB != null) {
                    iPB = PB.length;
                    Log.i("ESLImageTransfer", String.format(Locale.ENGLISH, "PB size = %d", PB.length));
                }
                binding.textview2Second.setText(String.format(Locale.ENGLISH, "Uncomp %d, G4 %d, PCX %d, PB %d", iUncomp, iG4, iPCX, iPB));
        } // bBitmapLoaded
     //       }
     //   });
    } /* UpdateBitmap() */

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if( requestCode == PICK_IMAGE_FILE ) {
            try {
                final Uri imageUri = data.getData();
                final InputStream imageStream = localContext.getContentResolver().openInputStream(imageUri);
                originalBitmap = BitmapFactory.decodeStream(imageStream);
                bBitmapLoaded = true;
                UpdateBitmap();
                binding.buttonSecond.setEnabled(true);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                Toast.makeText(localContext, "Something went wrong", Toast.LENGTH_LONG).show();
            }
        }else {
            Toast.makeText(localContext, "You haven't picked Image",Toast.LENGTH_LONG).show();
        }
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mHandler = new Handler();
        binding.buttonOpen.setEnabled(true);
        binding.buttonClear.setEnabled(true);
        binding.textviewSecond.setText(String.format(Locale.ENGLISH, "%s, %s", FirstFragment.eslDevice.getName(), szPanelNames[FirstFragment.iPanelType]));
        binding.buttonWeather.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Thread thread = new Thread(new Runnable() {
                    @Override public void run() {
                        try  {
                            String site = "https://wttr.in/?format=j1";
                            URL myURL = new URL(site);
                            String s = getResponseFromHttpUrl(myURL);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                thread.start();
            }
        });
        binding.buttonConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (bConnected) {
                    // Disconnect
                    Toast.makeText(localContext,
                            "Disconnecting from ESL...",
                            Toast.LENGTH_LONG).show();
                    mBluetoothGatt.disconnect();
                   // mBluetoothGatt.close();
                    bConnected = false;
                    binding.buttonSecond.setEnabled(false);
                    binding.buttonOpen.setEnabled(false);
                    binding.buttonClear.setEnabled(false);
                    binding.buttonConnect.setText(R.string.connect);
                } else {
                    Toast.makeText(localContext,
                            "Connecting to ESL...",
                            Toast.LENGTH_LONG).show();
                    if (connectGatt(FirstFragment.eslDevice.getAddress())) {
                        mBluetoothGatt.discoverServices();
                        Toast.makeText(localContext,
                                "Connected!",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(localContext,
                                "Connection failed!",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    binding.buttonConnect.setText(R.string.disconnect);
                }
            }
        });

        binding.buttonClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        clearImage();
                    }
                });
            }
        });

        binding.checkboxSecond.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                bDither = binding.checkboxSecond.isChecked();
                UpdateBitmap();
            }
        });

        binding.buttonOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");

                    // Optionally, specify a URI for the file that should appear in the
                    // system file picker when it loads.
                    //intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri);

                startActivityForResult(intent, PICK_IMAGE_FILE);
            }
        });
        binding.buttonSecond.setEnabled(false);
        binding.buttonSecond.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        sendImage();
                    }
                });
//                NavHostFragment.findNavController(SecondFragment.this)
//                        .navigate(R.id.action_SecondFragment_to_FirstFragment);
            }
        });
        localContext = getActivity().getApplicationContext();

        // Get BluetoothAdapter
        final BluetoothManager bluetoothManager =
                (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
    } /* onViewCreated() */

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
    /**
     * Gets the response from http Url request
     * @param url
     * @return
     * @throws IOException
     */
    private static String getResponseFromHttpUrl(URL url) throws IOException {

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.addRequestProperty("Accept","application/json");
        connection.addRequestProperty("Content-Type","application/json");
//        connection.addRequestProperty("Authorization","Bearer <spotify api key>");

        try {
            InputStream in = connection.getInputStream();

            Scanner scanner = new Scanner(in);
            scanner.useDelimiter("\\A");

            boolean hasInput = scanner.hasNext();
            if (hasInput) {
                String s = scanner.next();
                try {
                    Calendar calendar=Calendar.getInstance();
                    DateFormat df = new SimpleDateFormat("EEEE d/M/yy");
                    String sLocalTime = df.format(calendar.getTimeInMillis());
                    JSONObject jObject = new JSONObject(s);
                    JSONArray cc = jObject.getJSONArray("current_condition");
                    JSONObject current_condition = cc.getJSONObject(0);
                    JSONArray cw = jObject.getJSONArray("weather");
                    JSONObject weather = cw.getJSONObject(0);
                    JSONArray astro = weather.getJSONArray("astronomy");
                    JSONObject wa = astro.getJSONObject(0);
                    String sSunrise = wa.getString("sunrise");
                    String sSunset = wa.getString("sunset");
                    String sTemp = current_condition.getString("temp_C");
                    String sHum = current_condition.getString("humidity");
                    JSONArray cca = current_condition.getJSONArray("weatherDesc");
                    JSONObject cco = cca.getJSONObject(0);
                    String sDesc = cco.getString("value");
                    String sMinTemp = weather.getString("mintempC");
                    String sMaxTemp = weather.getString("maxtempC");
                    String sWind = current_condition.getString("windspeedKmph");
                    originalBitmap = Bitmap.createBitmap(
                            250, 122, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(originalBitmap);
 //                   Paint paint = new Paint();
 //                   paint.setAntiAlias(false);
                    originalBitmap.eraseColor(Color.WHITE);
 //                   paint.setColor(Color.BLACK);
 //                   paint.setTextSize(20);
 //                   canvas.drawText(sLocalTime, 0, 20, paint);
                    DrawBMFont(originalBitmap, 0, 2, sLocalTime, bmFont16);
 //                   paint.setTextSize(24);
                    String sTemperature = "Temp: " + sTemp + "C (" + sMinTemp + "/" + sMaxTemp + ")";
                    DrawBMFont(originalBitmap, 0, 20, sTemperature, bmFont16);
 //                   canvas.drawText(sTemperature, 0, 48, paint);
                    DrawBMFont(originalBitmap, 0, 40, "Wind: " + sWind + "kph Hum: " + sHum + "%", bmFont16);
 //                   canvas.drawText("Wind: " + sWind + "kph H: " + sHum + "%", 0, 86, paint);
                    DrawBMFont(originalBitmap, 0, 60, sDesc, bmFont16);
                    DrawBMFont(originalBitmap, 0, 80, "Sunrise: " + sSunrise, bmFont16);
                    DrawBMFont(originalBitmap, 0, 100, "Sunset: " + sSunset, bmFont16);
                    //                   canvas.drawText(sDesc, 0, 116, paint);
                    mHandler.post(new Runnable() {
                        public void run() {
                            try{
                                // **Do the GUI work here**
                                bBitmapLoaded = true;
                                UpdateBitmap();
                                binding.buttonSecond.setEnabled(true);
                            } catch (Exception e) { }
                        }});
                } catch (Exception e) {

                }
                return s;
            } else {
                return null;
            }
        } finally {
            connection.disconnect();
        }
    }

    private void clearImage() {
        byte[] command = {0x00};
        mCharacteristic.setValue(command);
        mCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mBluetoothGatt.writeCharacteristic(mCharacteristic);
    } /* clearImage() */

    public void sendNextPacket()
    {
        if (write_len > 0) {
            int size;
            byte[] imageData;
            byte flagbyte;

            if (write_offset == 0) { // first packet
                if (write_len <= iMaxPayload) // entire image fits in one packet
                    flagbyte = (byte)(-64 + comp_type);
                else
                   flagbyte = (byte) (64 + comp_type); // image type + first packet flag
            } else if (write_len <= iMaxPayload) // last packet
                flagbyte = (byte) (-128 + comp_type); // image type + last packet flag (0x81)
            else
                flagbyte = comp_type; // (middle of the transmission) image data
            size = iMaxPayload;
            if (size > write_len) {
                size = write_len;
            }
            imageData = new byte[size+1];
            imageData[0] = flagbyte;
            for (int i = 1; i <= size; i++) {
                imageData[i] = outbytes[write_offset++];
            }
            write_len -= size;
            mCharacteristic.setValue(imageData);
            mCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            mBluetoothGatt.writeCharacteristic(mCharacteristic);
        }
    } /* sendNextPacket() */

    private void sendImage() {
        // Try sending a blank screen command
        int size, iHeight;
        byte[] temp;
        byte[] imageData = new byte[iMaxPayload + 1];
        byte[] pixels = EPD_Image_Prep.PrepareImageData(theBitmap, (iPanelPlanes[FirstFragment.iPanelType] == 1) ? EPD_Image_Prep.EPDTYPE.EPD_BW : EPD_Image_Prep.EPDTYPE.EPD_BWR, (iIsRotated[FirstFragment.iPanelType] == 1) ? 90 : 0, false);

        iMaxPayload = iMTUSize-4; // by this point, the MTU size has been negotiated
        comp_type = EPD_UNCOMPRESSED; // assume uncompressed
        outbytes = pixels; // reference the same object
        temp = EPD_Image_Prep.CompressPCX(pixels);
        if (temp != null && temp.length < outbytes.length) {
            // use PCX compressed image data
            comp_type = EPD_PCX;
            outbytes = temp;
        }
        temp = EPD_Image_Prep.CompressPackBits(pixels);
        if (temp != null && temp.length < outbytes.length) {
            // use PackBits compressed image data
            comp_type = EPD_PB;
            outbytes = temp;
        }
        temp = EPD_Image_Prep.CompressG4(pixels, theBitmap.getWidth(), theBitmap.getHeight(), (iPanelPlanes[FirstFragment.iPanelType] == 1) ? EPD_Image_Prep.EPDTYPE.EPD_BW : EPD_Image_Prep.EPDTYPE.EPD_BWR, (iIsRotated[FirstFragment.iPanelType] == 1) ? 90 : 0);
        if (temp != null && temp.length < outbytes.length) {
            // use CCITT G4 compression
            comp_type = EPD_G4;
            outbytes = temp;
        }
        // Send the bitmap bytes that have been formatted and compressed for the EPD framebuffer
        write_offset = 0; // offset into source image data
        // total bytes to send
        write_len = outbytes.length;
        sendNextPacket(); // Start writing and let the write callback continue writing until the image is fully written
    } /* sendImage() */

    private boolean connectGatt(final String address) {
        if (mBluetoothAdapter == null || address == null) {
      //      Log.w(, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        if (mBluetoothGatt != null) {
        //    Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter
                .getRemoteDevice(address);
        if (device == null) {
       //     Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
// NB: This won't work with LE devices unless you use the SDK 23+ version of this function
        // and specify the transport type as LE
        mBluetoothGatt = device.connectGatt(localContext, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        // Log.d(TAG, "Trying to create a new connection.");
        return mBluetoothGatt.connect();
    }
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    //bluetooth is connected so discover services
                    mBluetoothGatt.requestMtu(512); // ask for the largest possible MTU
                    //  mBluetoothGatt.discoverServices();

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    //Bluetooth is disconnected
                    bConnected = false;
                    gatt.close();
                }
            } else {
                // Error - close the connection
                gatt.close();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            gatt.discoverServices();
            iMTUSize = mtu;
            Log.i("ESLImageTransfer", String.format(Locale.ENGLISH, "MTU size = %d", mtu));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // services are discovered, find the one we want
                final List<BluetoothGattService> services = gatt.getServices();
                Log.i("ESLImageTransfer", String.format(Locale.ENGLISH,"discovered %d services for ESL", services.size()));
                for (BluetoothGattService service : services) {
                    if (service.getUuid().equals(ESL_SERVICE_UUID)) {
                        service.getCharacteristics();
                        mCharacteristic = service.getCharacteristic(ESL_CHARACTERISTIC_UUID);
                        bConnected = true; // at this point we are officially connected
//                        mHandler.post(new Runnable() {
//                            @Override
//                            public void run() {
//                                if (bSendImage)
//                                    sendImage();
//                                else
//                                    clearImage();
//                            }
//                        });
                    }
                }
            } else {
                Log.e("ESLImageTransfer", "Service discovery failed");
                gatt.disconnect();
                return;
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
               // gatt.writeCharacteristic(characteristic); // write it again?
                Log.i("ESLImageTransfer", String.format(Locale.ENGLISH, "BLE write error: %d", status));
            }
            super.onCharacteristicWrite(gatt, characteristic, status);
            sendNextPacket();
        }
    };
}