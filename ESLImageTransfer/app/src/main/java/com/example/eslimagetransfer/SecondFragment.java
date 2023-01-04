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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
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
    private static boolean bSendImage = true; // false = clear, true = image
    private static final UUID ESL_SERVICE_UUID = UUID.fromString("13187b10-eba9-a3ba-044e-83d3217d9a38");
    private static final UUID ESL_CHARACTERISTIC_UUID = UUID.fromString("4b646063-6264-f3a7-8941-e65356ea82fe");
    // Request code for selecting an image file.
    private static final int PICK_IMAGE_FILE = 2;
    private static Bitmap theBitmap = null;
    private static Bitmap bmFont24, bmFont16;

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

    private Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
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
        bm.recycle();
        return resizedBitmap;
    }
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if( requestCode == PICK_IMAGE_FILE ) {
            try {
                final Uri imageUri = data.getData();
                final InputStream imageStream = localContext.getContentResolver().openInputStream(imageUri);
                Bitmap selectedImage = BitmapFactory.decodeStream(imageStream);
                theBitmap = getResizedBitmap(selectedImage, 250, 122);
                // scan through all pixels
                for (int x = 0; x < theBitmap.getWidth(); ++x) {
                    for (int y = 0; y < theBitmap.getHeight(); ++y) {
                        // get pixel color
                        int pixel, A, R, G, B;
                        pixel = theBitmap.getPixel(x, y);
                        A = Color.alpha(pixel);
                        R = Color.red(pixel);
                        G = Color.green(pixel);
                        B = Color.blue(pixel);
                        int gray = (int) (0.2989 * R + 0.5870 * G + 0.1140 * B);

                        // use 128 as threshold, above -> white, below -> black
                        if (gray > 128)
                            gray = 255;
                        else
                            gray = 0;
                        // set new pixel color to output bitmap
                        theBitmap.setPixel(x, y, Color.argb(gray, gray, gray, gray));
                    }
                }
                binding.imageviewSecond.setImageBitmap(theBitmap);
                bBitmapLoaded = true;
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

        binding.buttonClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                bSendImage = false; // tell final step to clear display
                Toast.makeText(localContext,
                        "Connecting to ESL...",
                        Toast.LENGTH_LONG).show();
                if (connectGatt(FirstFragment.eslDevice.getAddress())) {
                    mBluetoothGatt.discoverServices();
                    Toast.makeText(localContext,
                            "Connected!",
                            Toast.LENGTH_LONG).show();
                    bConnected = true;
                } else {
                    Toast.makeText(localContext,
                            "Connection failed!",
                            Toast.LENGTH_LONG).show();
                    return;
                }
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
                Toast.makeText(localContext,
                        "Connecting to ESL...",
                        Toast.LENGTH_LONG).show();
                if (connectGatt(FirstFragment.eslDevice.getAddress())) {
                    mBluetoothGatt.discoverServices();
                    Toast.makeText(localContext,
                            "Connected!",
                            Toast.LENGTH_LONG).show();
                    bConnected = true;
                } else {
                    Toast.makeText(localContext,
                            "Connection failed!",
                            Toast.LENGTH_LONG).show();
                    return;
                }
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
                    theBitmap = Bitmap.createBitmap(
                            250, 122, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(theBitmap);
 //                   Paint paint = new Paint();
 //                   paint.setAntiAlias(false);
                    theBitmap.eraseColor(Color.WHITE);
 //                   paint.setColor(Color.BLACK);
 //                   paint.setTextSize(20);
 //                   canvas.drawText(sLocalTime, 0, 20, paint);
                    DrawBMFont(theBitmap, 0, 2, sLocalTime, bmFont16);
 //                   paint.setTextSize(24);
                    String sTemperature = "Temp: " + sTemp + "C (" + sMinTemp + "/" + sMaxTemp + ")";
                    DrawBMFont(theBitmap, 0, 20, sTemperature, bmFont16);
 //                   canvas.drawText(sTemperature, 0, 48, paint);
                    DrawBMFont(theBitmap, 0, 40, "Wind: " + sWind + "kph Hum: " + sHum + "%", bmFont16);
 //                   canvas.drawText("Wind: " + sWind + "kph H: " + sHum + "%", 0, 86, paint);
                    DrawBMFont(theBitmap, 0, 60, sDesc, bmFont16);
                    DrawBMFont(theBitmap, 0, 80, "Sunrise: " + sSunrise, bmFont16);
                    DrawBMFont(theBitmap, 0, 100, "Sunset: " + sSunset, bmFont16);
                    //                   canvas.drawText(sDesc, 0, 116, paint);
                    mHandler.post(new Runnable() {
                        public void run() {
                            try{
                                // **Do the GUI work here**
                                bBitmapLoaded = true;
                                binding.imageviewSecond.setImageBitmap(theBitmap);
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

    private void sendImage() {
        // Try sending a blank screen command
        byte[] setBytePos = {0x02, 0x00, 0x00};
        byte[] display = {0x01};
        byte[] imageData = new byte[17];
        // create a 16x16 pattern of black and white squares
        mCharacteristic.setValue(setBytePos);
        mCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        mBluetoothGatt.writeCharacteristic(mCharacteristic);
        try {
            //thread to sleep for the specified number of milliseconds
            Thread.sleep(5);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Send the bitmap by working from right to left, top to bottom
        imageData[0] = 0x03; // image data command
        for (int x=249; x>=0; x--) {
            final byte[] bMasks = {-128, 64, 32, 16, 8, 4, 2, 1};
            byte bPixels = 0;
            int pixel, off = 1;
            for (int y=0; y<122; y++) {
                pixel = theBitmap.getPixel(x, y) & 0xffffff; // mask off alpha
                if (pixel != 0)
                    bPixels |= bMasks[y & 7];
                if ((y & 7) == 7) { // time to write the completed byte
                    imageData[off++] = bPixels;
                    bPixels = 0;
                }
            } // for y
            imageData[off] = bPixels; // store the last partial byte
            mCharacteristic.setValue(imageData);
            mCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            mBluetoothGatt.writeCharacteristic(mCharacteristic);
            try {
                //thread to sleep for the specified number of milliseconds
                Thread.sleep(5);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } // for x
        // show the image
        mCharacteristic.setValue(display);
        mCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        mBluetoothGatt.writeCharacteristic(mCharacteristic);

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
                    bConnected = true;
                    mBluetoothGatt.discoverServices();

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
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // services are discovered, find the one we want
                final List<BluetoothGattService> services = gatt.getServices();
                Log.i("ESLImageTransfer", String.format(Locale.ENGLISH,"discovered %d services for ESL", services.size()));
                for (BluetoothGattService service : services) {
                    if (service.getUuid().equals(ESL_SERVICE_UUID)) {
                        service.getCharacteristics();
                        mCharacteristic = service.getCharacteristic(ESL_CHARACTERISTIC_UUID);
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (bSendImage)
                                    sendImage();
                                else
                                    clearImage();
                                // shut down this fragment's activity until we re-scan + re-connect
                                mBluetoothGatt.disconnect();
                                mBluetoothGatt.close();
                                binding.buttonSecond.setEnabled(false);
                                binding.buttonOpen.setEnabled(false);
                                binding.buttonClear.setEnabled(false);
                            }
                        });
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
            super.onCharacteristicWrite(gatt, characteristic, status);

        }
    };
}