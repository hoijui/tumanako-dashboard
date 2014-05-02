package com.tumanako.sensors;

/************************************************************************************
Tumanako - Electric Vehicle and Motor control software

Copyright (C) 2012 Jeremy Cole-Baker <jeremy@rhtech.co.nz>

This file is part of Tumanako Dashboard.

Tumanako is free software: you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published
by the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Tumanako is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License
along with Tumanako.  If not, see <http://www.gnu.org/licenses/>.

*************************************************************************************/

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import com.tumanako.dash.ChargeNode;
import com.tumanako.dash.DashMessages;
import com.tumanako.dash.IDashMessages;

/**
 * Data Input Service:
 *
 * This class creates a service which manages data generation / input / processing.
 * The Tumanako application may get data from several sources:
 *
 *  -A connection to the vehicle (USB, Bluetooth, etc)
 *  -Built in sensors in the Android device (GPS, accelerometer, etc)
 *  -Other sources (website, external GPS, etc).
 *
 * We want to seperate the data input and processing from the UI drawing.
 * This allows us to 'connect' to the data sources when our application is in the
 * foreground, and keep our connections alive during configuration changes
 * such as screen rotates, changing pages, etc.
 *
 * When all our application activities are closed or in the background, our data
 * connections should be suspended so that they don't waste CPU time and
 * battery power if the user is doing something else with their Android
 * device.
 *
 * To achieve the above, this class implements a Service. We'll start it
 * explicitly with 'startService' so that it's not closed when any
 * particular UI Activity is closed / redrawn. We'll use an internal
 * 'Watchdog' mechanism that will automatically stop the service if no
 * UI activity interacts with it for more than a certain time length.
 *
 *  Note: The service model could be extended in the future to allow
 *  it to keep running while the UI was in the background, for example
 *  if we wanted to log data to a file even when the user was doing
 *  something else.
 *
 * @author Jeremy
 */
public class DataService extends Service implements IDashMessages
{

  // Data Sources we'd like to use
  public NmeaGPS     deviceGPS;
  public VehicleData vehicleData;
  public ChargeNode  chargeNode;

  /** Internal update timer: creates a 'refresh' interval on which we will do things. */
  private final Handler updateTimer = new Handler();
  /** Used to track sensor events; must be explicitly reset periodically by client applications. */
  private int watchdogCounter = 0;

  /** Carry out update every n mSeconds. */
  private static final int UPDATE_INTERVAL = 2000;
  /** Watchdog will cause service to stop if not reset for n x UPDATE_INTERVAL */
  private static final int WATCHDOG_OVERFLOW = 3;

  /** An Intent with this Action is generated by the UI to tell the service that it's still needed. */
  public static final String DATA_SERVICE = "com.tumanako.sensors.dataservice";
  public static final int    DATA_SERVICE_KEEPALIVE = 1;

  private DashMessages dashMessages;

//  /** An Intent with this Action is generated by the UI to tell the service that it's still needed. */
//  public static final String DATA_SERVICE_KEEPALIVE = "com.tumanako.sensors.serviceKeepAlive";

  /**
   * Create a Binder object to receive interactions from client.
   * LocalBinder is a private internal class defined below.
   */
  private final IBinder mBinder = (IBinder) new LocalBinder();

  // ---------------DEMO MODE CODE -------------------------------
  public static final int DATA_SERVICE_DEMO = 1;
  private DemoData demoData;
  // ---------------DEMO MODE CODE -------------------------------

  public DataService()
  {
    Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> Constructor; ");
  }

  /**
   * Keep Alive Method.
   * UI pages should connect to the service
   * and call this occasionally to prevent
   * the service timing out.
   */
  public void keepAlive()
  {
    watchdogCounter = 0; // Reset the watchdog timer.
  }

  // ---------------DEMO MODE CODE -------------------------------
  public void setDemo(boolean thisIsDemo)
  {
    // Set the 'Demo' mode flag:
    deviceGPS.NMEAData.setDemo(thisIsDemo);
    demoData.setDemo(thisIsDemo);
  }
  // ---------------DEMO MODE CODE -------------------------------


  private void startSensors()
  {
    deviceGPS.resume();
    startVehicleData();
    chargeNode.resume();
  }

  private void stopSensors()
  {
    deviceGPS.suspend();
    chargeNode.suspend();
    // ---------------DEMO MODE CODE -------------------------------
    setDemo(false);
    // ---------------DEMO MODE CODE -------------------------------
  }

  private void startVehicleData()
  {
    // Create new Vehicle Data connection:
    if (vehicleData != null) {
      // A vehicle data conneciton already exists. Check whether it is active:
      if (vehicleData.isFinished()) vehicleData = null;
          // The BT thread has already stopped itself.
          // Note that it may not be safe to discard the object (i.e. set it to null)
          // if the thread is still active - hence we need to check isFinished.
    }
    // If the vehicle data connection doesn't exist at this point, create a new one:
    if (vehicleData == null) vehicleData = new VehicleData(this);
  }

  private final Runnable updateTimerTask = new Runnable()
  {
    @Override
    public void run()
    {
      // Update Watchdog counter and check for overflow:
      updateStop();
      watchdogCounter++;
//Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> Timer. Counter = " + watchdogCounter);
      if (watchdogCounter > WATCHDOG_OVERFLOW) {
        // Watchdog Expired!
        stopSensors();        // Stop the sensors.
        stopSelf();           // Stop the service!
      } else {
        /* ******** Check Bluetooth Connection: **********
         * We call startVehicleData() to check the bluetooth connection.
         * Note that if it's already connected and active, startVehicleData()
         * won't do anything. If the connection has been lost and the
         * bluetooth thread has stopped (isFinished = true), the vehicle
         * data object is discarded and recreated.
         ***********************************************/
        startVehicleData();

        // Restart the update timer:
        updateStart();
      }
    }
  };

  private void updateStart()
  {
    updateTimer.removeCallbacks(updateTimerTask);
    updateTimer.postDelayed(updateTimerTask, UPDATE_INTERVAL);
  }

  private void updateStop()
  {
    updateTimer.removeCallbacks(updateTimerTask);
  }


  /**
   * Private BINDER class: Used to return a binder
   * so clients can interact with us!
   * The IBinder interface specifies that we must
   * implement one method called 'getService()'
   * which returns a reference to our service:
   *
   * @author Jeremy Cole-Baker / Riverhead Technology
   */
  public class LocalBinder extends Binder
  {
    public DataService getService()
    {
      return DataService.this;
    }
  }

  /* ********************************************************************
   * Service Methods:
   * We override the following Service methods to respond to various
   * sensor events.
   ********************************************************************/

  @Override
  public void onCreate()
  {
    // Sensor Service CREATED:
    Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> onCreate(); ");
    // Add some sensors:
    deviceGPS    = new NmeaGPS(this);
    dashMessages = new DashMessages(this, this, DATA_SERVICE);
    demoData     = new DemoData(this);
    chargeNode   = new ChargeNode(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    // Start Command: Someone requested that the serice be started:
    super.onStartCommand(intent, flags, startId);
    Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> Received start id " + startId + ": " + intent);
    dashMessages.resume();
    updateStart();
    startSensors();         // Start the Sensors (if not already started!)
    // We want this service to continue running until it is explicitly stopped, so return 'sticky':
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent)
  {
    Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> onBind(); " );
    dashMessages.resume();
    updateStart();         // Start Update Timer
    startSensors();        // Start the Sensors (if not already started!)
    return mBinder;
  }

  @Override
  public boolean onUnbind (Intent intent)
  {
    // UNBIND: A client has unbound. Don't need to do anything.
    Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> onUnBind(); " );
    return true;
  }

  @Override
  public void onRebind (Intent intent)
  {
    Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> onReBind(); " );
    // REBIND: A client has reconnected to the service after disconnecting.
    dashMessages.resume();
    updateStart();         // Start update timer.
    startSensors();         // Start the Sensors (if not already started!)
  }

  @Override
  public void onDestroy()
  {
    // Service DESTROYED:
    Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> onDestroy(); " );
    // Unregister the Intent listener since the service is about to be destroyed.
    dashMessages.suspend();
    updateStop();          // Stop update timer.
    stopSensors();          // Stop the sensors.
    deviceGPS = null;
    vehicleData = null;
    chargeNode = null;
  }

  @Override
  public void messageReceived(String action, int message, Float floatData, String stringData, Bundle data)
  {
    //Log.i(com.tumanako.ui.UIActivity.APP_TAG, " DataService -> KeepAlive!" );
    keepAlive();
    // ---------------DEMO MODE CODE -------------------------------
    if ((message == DATA_SERVICE_DEMO) && (floatData != null)) {
      // An intent to turn demo mode on or off.
      if (floatData == 0f) {
        setDemo(false);
      } else {
        setDemo(true);
      }
    }
    // ---------------DEMO MODE CODE -------------------------------
  }
}
