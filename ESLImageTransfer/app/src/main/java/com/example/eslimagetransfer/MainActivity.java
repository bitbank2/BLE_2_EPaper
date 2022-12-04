package com.example.eslimagetransfer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.view.View;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.example.eslimagetransfer.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;
    private static final int RQS_ENABLE_BLUETOOTH = 1;
    private static final int RQS_BLUETOOTH_SCAN = 2;
    private static final int RQS_BLUETOOTH = 3;
    private static final int RQS_FINE_LOCATION = 4;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        binding.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });
        // Check if BLE is supported on the device.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this,
                    "BLUETOOTH_LE not supported in this device!",
                    Toast.LENGTH_SHORT).show();
            finish();
        }
        showPhoneStatePermission();

        getBluetoothAdapterAndLeScanner();
        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this,
                    "bluetoothManager.getAdapter()==null",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
    } /* onCreate() */

    private void showPhoneStatePermission() {
        int permissionCheck = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.BLUETOOTH)) {
                showExplanation("Bluetooth permission Needed", "This app can only operate if BT is enabled.", Manifest.permission.BLUETOOTH_SCAN, RQS_BLUETOOTH_SCAN);
            } else {
                requestPermission(Manifest.permission.BLUETOOTH, RQS_BLUETOOTH);
            }
        } else {
            Toast.makeText(MainActivity.this, "BT Permission (already) Granted!", Toast.LENGTH_SHORT).show();
        }
        permissionCheck = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                showExplanation("Fine location permission Needed", "BLE needs fine location to work on Android.", Manifest.permission.ACCESS_FINE_LOCATION, RQS_FINE_LOCATION);
            } else {
                requestPermission(Manifest.permission.ACCESS_FINE_LOCATION, RQS_FINE_LOCATION);
            }
        } else {
            Toast.makeText(MainActivity.this, "BT Permission (already) Granted!", Toast.LENGTH_SHORT).show();
        }
        permissionCheck = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.BLUETOOTH_SCAN)) {
                showExplanation("BLE Scan permission Needed", "In order to send image data to ESLs, BLE scanning is required.", Manifest.permission.BLUETOOTH_SCAN, RQS_BLUETOOTH_SCAN);
            } else {
                requestPermission(Manifest.permission.BLUETOOTH_SCAN, RQS_BLUETOOTH_SCAN);
            }
        } else {
            Toast.makeText(MainActivity.this, "BT Scan Permission (already) Granted!", Toast.LENGTH_SHORT).show();
        }
        permissionCheck = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.BLUETOOTH_CONNECT)) {
                showExplanation("BLE Connect permission Needed", "In order to send image data to ESLs, BLE connection is required.", Manifest.permission.BLUETOOTH_CONNECT, RQS_BLUETOOTH_SCAN);
            } else {
                requestPermission(Manifest.permission.BLUETOOTH_CONNECT, RQS_BLUETOOTH_SCAN);
            }
        } else {
            Toast.makeText(MainActivity.this, "BT Connect Permission (already) Granted!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String permissions[],
            int[] grantResults) {
        switch (requestCode) {
            case RQS_ENABLE_BLUETOOTH:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "BT Permission Granted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "BT Permission Denied!", Toast.LENGTH_SHORT).show();
                }
                break;
            case RQS_BLUETOOTH_SCAN:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "BT Scan Permission Granted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "BT Scan Permission Denied!", Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    private void showExplanation(String title,
                                 String message,
                                 final String permission,
                                 final int permissionRequestCode) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        requestPermission(permission, permissionRequestCode);
                    }
                });
        builder.create().show();
    }

    private void requestPermission(String permissionName, int permissionRequestCode) {
        ActivityCompat.requestPermissions(this,
                new String[]{permissionName}, permissionRequestCode);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, RQS_ENABLE_BLUETOOTH);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == RQS_ENABLE_BLUETOOTH && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        getBluetoothAdapterAndLeScanner();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this,
                    "bluetoothManager.getAdapter()==null",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }


        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }
    private void getBluetoothAdapterAndLeScanner(){
        // Get BluetoothAdapter and BluetoothLeScanner.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        //mScanning = false;
    }

} /* class */