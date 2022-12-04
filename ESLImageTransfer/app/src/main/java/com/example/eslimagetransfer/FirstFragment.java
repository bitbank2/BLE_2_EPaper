package com.example.eslimagetransfer;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.eslimagetransfer.databinding.FragmentFirstBinding;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

public class FirstFragment extends Fragment {

    private FragmentFirstBinding binding;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;

    private boolean mScanning;

    private static final int RQS_ENABLE_BLUETOOTH = 1;

    List<BluetoothDevice> listBluetoothDevice;
    List<String> listBTName;
    List<String> listFavoritesName;
    List<BluetoothDevice> listFavoritesBT;
    ListAdapter adapterLeScanResult;
    ListAdapter adapterFavorites;

    private Handler mHandler;
    private static final long SCAN_PERIOD = 5000;
    private static Context localContext;
    public static BluetoothDevice eslDevice;
    public static int iSelectedPos;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentFirstBinding.inflate(inflater, container, false);
        eslDevice = null;
        return binding.getRoot();

    }

    private void ReadFavorites()
    {
    SharedPreferences prefs;
        prefs =  localContext.getSharedPreferences("ESLTransfer", Context.MODE_PRIVATE);
        int iCount = prefs.getInt("Count", 0); // how many items in the list?
        for (int i=0; i<iCount; i++) {
            String s = "name" + i;
            String mac = prefs.getString(s, null);
            BluetoothDevice btDevice = mBluetoothAdapter.getRemoteDevice(mac);
            // Create the name from the MAC address
            String btName = "ESL_" + mac.substring(9, 11) + mac.substring(12, 14) + mac.substring(15, 17);
            listFavoritesBT.add(btDevice);
            listFavoritesName.add(btName);
        }
        binding.favslist.invalidateViews();
        binding.favslist.deferNotifyDataSetChanged();
    } /* ReadFavorites() */

    private void WriteFavorites()
    {
        SharedPreferences prefs;
        prefs =  localContext.getSharedPreferences("ESLTransfer", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor;
        editor = prefs.edit();
        int iCount = listFavoritesName.size();
        editor.clear();
        editor.putInt("Count", iCount);
        for (int i=0; i<iCount; i++) {
            String s = "name" + i;
            BluetoothDevice device = (BluetoothDevice) listFavoritesBT.get(i);
            String mac = device.getAddress();
            editor.putString(s, mac);
        }
        editor.commit();
    } /* WriteFavorites() */

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonFirst.setEnabled(false);
        binding.buttonFavorite.setEnabled(false);
        binding.buttonFavorite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                BluetoothDevice device = (BluetoothDevice) listBluetoothDevice.get(iSelectedPos);
                if(!listFavoritesBT.contains(device)){
                    listFavoritesBT.add(device);
                    String name = (String)listBTName.get(iSelectedPos);
                    listFavoritesName.add(name);
                    binding.favslist.invalidateViews();
                    binding.favslist.deferNotifyDataSetChanged();
                    WriteFavorites();
                }
            }
        });

        binding.buttonFirst.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                NavHostFragment.findNavController(FirstFragment.this)
                        .navigate(R.id.action_FirstFragment_to_SecondFragment);
            }
        });
        binding.scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Check if the adapter wasn't created at create time
                if (mBluetoothAdapter != null && mBluetoothLeScanner == null) {
                    mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
                    if (mBluetoothLeScanner == null) {
                        Toast.makeText(localContext,
                                "No permission to scan BLE",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                scanLeDevice(true);
            }
        });

        listBluetoothDevice = new ArrayList<>();
        listBTName = new ArrayList<>();
        listFavoritesBT = new ArrayList<>();
        listFavoritesName = new ArrayList<>();

        adapterLeScanResult = new ArrayAdapter<String>(
                getActivity(), android.R.layout.simple_list_item_1, listBTName);
        adapterFavorites = new ArrayAdapter<String>(
                getActivity(), android.R.layout.simple_list_item_1, listFavoritesName);

        binding.lelist.setAdapter(adapterLeScanResult);
        binding.lelist.setOnItemClickListener(scanResultOnItemClickListener);
        binding.favslist.setAdapter(adapterFavorites);
        binding.favslist.setOnItemClickListener(favoritesOnItemClickListener);

        mHandler = new Handler();

        localContext = getActivity().getApplicationContext();
        // Get BluetoothAdapter and BluetoothLeScanner.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (mBluetoothAdapter != null)
            mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        mScanning = false;
        // Read and display the favorite devices
        ReadFavorites();
    } /* onViewCreated() */

    AdapterView.OnItemClickListener scanResultOnItemClickListener =
            new AdapterView.OnItemClickListener(){

                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    final BluetoothDevice device = (BluetoothDevice) listBluetoothDevice.get(position);
                    eslDevice = device; //keep this for later
                    binding.buttonFavorite.setEnabled(true); // allow it to be added to favorites
                    if (mScanning == false)
                        binding.buttonFirst.setEnabled(true);
//                    String msg = device.getAddress() + "\n"
//                            + device.getBluetoothClass().toString() + "\n"
//                            + getBTDeviceType(device);

 //                   new AlertDialog.Builder(getActivity())
 //                           .setTitle(device.getName())
 //                           .setMessage(msg)
 //                           .setPositiveButton("OK", new DialogInterface.OnClickListener() {
 //                               @Override
 //                               public void onClick(DialogInterface dialog, int which) {
//                                }
//                            })
//                            .show();

                }
            };

    AdapterView.OnItemClickListener favoritesOnItemClickListener =
            new AdapterView.OnItemClickListener(){
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    final BluetoothDevice device = (BluetoothDevice) listFavoritesBT.get(position);
                    eslDevice = device; //keep this for later
                    iSelectedPos = position;
                    binding.buttonFirst.setEnabled(true);
                }
            };

    private String getBTDeviceType(BluetoothDevice d){
        String type = "";

        switch (d.getType()){
            case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                type = "DEVICE_TYPE_CLASSIC";
                break;
            case BluetoothDevice.DEVICE_TYPE_DUAL:
                type = "DEVICE_TYPE_DUAL";
                break;
            case BluetoothDevice.DEVICE_TYPE_LE:
                type = "DEVICE_TYPE_LE";
                break;
            case BluetoothDevice.DEVICE_TYPE_UNKNOWN:
                type = "DEVICE_TYPE_UNKNOWN";
                break;
            default:
                type = "unknown...";
        }

        return type;
    }

    /*
     to call startScan (ScanCallback callback),
     Requires BLUETOOTH_ADMIN permission.
     Must hold ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION permission to get results.
      */
    private void scanLeDevice(final boolean enable) {
        if (enable) {
            listBluetoothDevice.clear();
            listBTName.clear();
            binding.lelist.invalidateViews();

            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mBluetoothLeScanner.stopScan(scanCallback);
                    binding.lelist.invalidateViews();

                    Toast.makeText(localContext,
                            "Scan Complete",
                            Toast.LENGTH_LONG).show();

                    mScanning = false;
                    binding.scan.setEnabled(true);
                    if (eslDevice != null)
                        binding.buttonFirst.setEnabled(true);
                }
            }, SCAN_PERIOD);

            mBluetoothLeScanner.startScan(scanCallback);
            mScanning = true;
            binding.scan.setEnabled(false);
        } else {
            mBluetoothLeScanner.stopScan(scanCallback);
            mScanning = false;
            binding.scan.setEnabled(true);
        }
    }

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            addBluetoothDevice(result.getDevice());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for(ScanResult result : results){
                addBluetoothDevice(result.getDevice());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Toast.makeText(localContext,
                    "onScanFailed: " + String.valueOf(errorCode),
                    Toast.LENGTH_LONG).show();
        }

        private void addBluetoothDevice(BluetoothDevice device){
            String deviceName = device.getName();
            if (deviceName == null) { // use the address
                deviceName = device.toString();
            }
            if(deviceName.startsWith("ESL_") && !listBluetoothDevice.contains(device)){
                listBluetoothDevice.add(device);
                listBTName.add(deviceName);
                binding.lelist.invalidateViews();
                binding.lelist.deferNotifyDataSetChanged();
            }
        }
    };

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

} /* FirstFragment class */