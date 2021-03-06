/*
 * Copyright 2014 Google Inc. All Rights Reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.smus.physicalweb;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Keeps track of all devices nearby.
 *
 * Posts notifications when a new device is near, or if an old device is no
 * longer nearby.
 *
 * Created by smus on 1/24/14.
 */
public class NearbyDeviceManager {
  private String TAG = "NearbyDeviceManager";

  private BluetoothAdapter mBluetoothAdapter;
  private int REQUEST_ENABLE_BT = 0;
  private Timer mExpireTimer;
  private Timer mSearchTimer;
  private Handler mQueryHandler;
  private boolean mIsSearching = false;

  private NearbyDeviceAdapter mNearbyDeviceAdapter;
  private OnNearbyDeviceChangeListener mListener;

  //
  private ArrayList<NearbyDevice> mDeviceBatchList;

  private Activity mActivity;

  private boolean mIsQueuing = false;
  // How often we should batch requests for metadata.
  private int QUERY_PERIOD = 500;
  // How often to search for new devices (ms).
  private int SEARCH_PERIOD = 5000;
  // How often to check for expired devices.
  private int EXPIRE_PERIOD = 3000;
  // How much time has to pass with a nearby device not being discovered before
  // we declare it gone.
  public static int MAX_INACTIVE_TIME = 10000;

  /**
   * The public interface of this class follows:
   */
  NearbyDeviceManager(Activity activity) {
    // Initializes Bluetooth adapter.
    final BluetoothManager bluetoothManager =
        (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
    mBluetoothAdapter = bluetoothManager.getAdapter();


    // Ensures Bluetooth is available on the device and it is enabled. If not,
    // displays a dialog requesting user permission to enable Bluetooth.
    if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
      Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
      activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
    }

    mDeviceBatchList = new ArrayList<NearbyDevice>();
    mNearbyDeviceAdapter = new NearbyDeviceAdapter(activity);
    mQueryHandler = new Handler();
    mSearchTimer = new Timer();
    mExpireTimer = new Timer();
    mActivity = activity;
  }

  /**
   * Set up a listener for new nearby devices coming and going.
   * @param listener
   */
  public void setOnNearbyDeviceChangeListener(OnNearbyDeviceChangeListener listener) {
    mListener = listener;

  }

  public interface OnNearbyDeviceChangeListener {
    public void onDeviceFound(NearbyDevice device);
    public void onDeviceLost(NearbyDevice device);
  }

  public void startSearchingForDevices() {
    assert !mIsSearching;
    mIsSearching = true;

    // Start a timer to do scans.
    mSearchTimer.scheduleAtFixedRate(mSearchTask, 0, SEARCH_PERIOD);
    // Start a timer to check for expired devices.
    mExpireTimer.scheduleAtFixedRate(mExpireTask, 0, EXPIRE_PERIOD);
  }

  public void stopSearchingForDevices() {
    assert mIsSearching;
    mIsSearching = false;
    mBluetoothAdapter.stopLeScan(mLeScanCallback);

    // Stop expired device timer.
    mExpireTimer.cancel();
    mSearchTimer.cancel();
  }

  public NearbyDeviceAdapter getAdapter() {
    return mNearbyDeviceAdapter;
  }

  public void scanDebug() {
    mSearchTask.run();
  }

  public void foundDeviceDebug(NearbyDevice debugDevice) {
    handleDeviceFound(debugDevice);
  }

  /**
   * Private methods follow:
   */
  private TimerTask mSearchTask = new TimerTask() {
    @Override
    public void run() {
      mBluetoothAdapter.stopLeScan(mLeScanCallback);
      boolean result = mBluetoothAdapter.startLeScan(mLeScanCallback);
      if (!result) {
        Log.e(TAG, "startLeScan failed.");
      }
    }
  };

  private TimerTask mExpireTask = new TimerTask() {
    @Override
    public void run() {
      ArrayList<NearbyDevice> removed = mNearbyDeviceAdapter.removeExpiredDevices();
      for (NearbyDevice device : removed) {
        mListener.onDeviceLost(device);
      }
    }
  };


  private Runnable mBatchMetadataRunnable = new Runnable () {
    @Override
    public void run() {
      batchFetchMetaData();
      mIsQueuing = false;
    }
  };

  private void batchFetchMetaData() {
    if(mDeviceBatchList.size() > 0) {
      MetadataResolver.getBatchMetadata(mDeviceBatchList);
      mDeviceBatchList = new ArrayList<NearbyDevice>(); // Clear out the list
    }
  }


  // NearbyDevice scan callback.
  private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
    @Override
    public void onLeScan(final BluetoothDevice device, final int RSSI, byte[] scanRecord) {
      Log.i(TAG, String.format("onLeScan: %s, RSSI: %d", device.getName(), RSSI));
      assert mListener != null;

      NearbyDevice candidateNearbyDevice = new NearbyDevice(device, RSSI);
      handleDeviceFound(candidateNearbyDevice);
    }
  };

  private void handleDeviceFound(NearbyDevice candidateNearbyDevice) {
    NearbyDevice nearbyDevice = mNearbyDeviceAdapter.getExistingDevice(candidateNearbyDevice);

    // Check if this is a new device.
    if (nearbyDevice != null) {
      // For existing devices, update their RSSI.
      nearbyDevice.updateLastSeen(candidateNearbyDevice.getLastRSSI());
      mNearbyDeviceAdapter.updateListUI();
    } else {
      // For new devices, add the device to the adapter.
      nearbyDevice = candidateNearbyDevice;
      if (nearbyDevice.isBroadcastingUrl()) {
        if (!mIsQueuing) {
          mIsQueuing = true;
          // We wait QUERY_PERIOD ms to see if any other devices are discovered so we can batch.
          mQueryHandler.postAtTime(mBatchMetadataRunnable, QUERY_PERIOD);
        }
        // Add the device to the queue of devices to look for.
        mDeviceBatchList.add(nearbyDevice);
        mNearbyDeviceAdapter.addDevice(nearbyDevice);
        mListener.onDeviceFound(nearbyDevice);
      }
    }
  }
}
