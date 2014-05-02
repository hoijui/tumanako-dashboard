package com.tumanako.dash;

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

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import com.tumanako.ui.UIActivity;
import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.Queue;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Charge Node Module.
 *
 * A WebView control displays data supplied by the charge node (HTML).
 * Buttons in the UI generate intents which this class catches.
 *
 *  -Connect to Node: Look for the server and display its web page
 *   if found. NOTE: Must already have a WiFi connection to the node.
 *
 *  -Start Charge: Tell the node that we want some power!
 *
 *  -Stop Charge: Tell the node that we've had enough.
 *
 *  While connected to the node, we will periodically poll it to
 *  get some status information.
 *
 * A separate thread will be used to handle network operations. beware
 * thread safety!
 *
 * @author Jeremy Cole-Baker / Riverhead Technology
 */
public class ChargeNode implements IDashMessages
{

  // ************** Action strings to identify Intents used by this class **************************
  /** An Intent with this Action is generated by the UI to send messages to this class. */
  public static final String CHARGE_NODE_INTENT     = "com.tumanako.dash.chargenode";

  // ************* Message IDs: Used to identify specific intent messages.
  /** Send by the UI: tells us to keep alive. */
  public static final int CHARGE_NODE_KEEPALIVE    = IDashMessages.CHARGE_NODE_ID +  1;
  /** Send by the UI: tells us to connect to server. */
  public static final int CHARGE_NODE_CONNECT      = IDashMessages.CHARGE_NODE_ID +  2;
  /** Send by the UI: tells us to start charging. */
  public static final int CHARGE_NODE_CHARGESTART  = IDashMessages.CHARGE_NODE_ID +  3;
  /** Send by the UI: tells us to stop charging. */
  public static final int CHARGE_NODE_CHARGESTOP   = IDashMessages.CHARGE_NODE_ID +  4;
  /** Passed to, and returned by, the ChargerHTTPConn class. */
  public static final int CHARGE_NODE_HTML_DATA    = IDashMessages.CHARGE_NODE_ID +  5;
  /** Indicate the data type we expect back from the request. */
  public static final int CHARGE_NODE_JSON_DATA    = IDashMessages.CHARGE_NODE_ID +  6;
  // ************* Charger Data Tags: **************************************
  // These are used as keys when sending data to the UI as a bundle.
  public static final String CONNECTION_STATUS = "CONSTAT";
  public static final String CHARGE_STATUS     = "CHGSTAT";
  public static final String CHARGE_CURRENT    = "CHGCUR";
  public static final String CHARGE_AH         = "CHGAH";
  // ************ Status Constants: **************************************
  public static final int STATUS_OFFLINE      = 0;
  public static final int STATUS_CONNECTING   = 1;
  public static final int STATUS_CONNECTED    = 2;
  public static final int STATUS_NOT_CHARGING = 3;
  public static final int STATUS_CHARGING     = 4;

  public static final String CHARGE_NODE_DEFAULT_HTML    = "<html><body></body></html>";
  public static final String CHARGE_NODE_CONNECT_HTML    = "<html><body><p>Connecting...</p></body></html>";
  public static final String CHARGE_NODE_CONNECTED_HTML  = "<html><body><p>Connected!</p></body></html>";
  public static final String CHARGE_NODE_LOGINERROR_HTML = "<html><body><p>Login Error.</p></body></html>";
  public static final String CHARGE_NODE_UPDATING_HTML   = "<html><body><p>Updating...</p></body></html>";
  public static final String CHARGE_NODE_OK_HTML         = "<html><body><p>OK</p></body></html>";

  /** URL to send username and password to for login. */
  private static final String LOGIN_URL = "http://data.solarnetwork.net/solarreg/j_spring_security_check";
  /** URL to get status updates. */
  private static final String PING_URL = "http://data.solarnetwork.net/solarquery/hardwareControlData.json?nodeId=30&mostRecent=true";

  private DashMessages dashMessages;

  /** UI Update timer interval (mS) */
  private static final int UPDATE_TIME = 500;
  /** Send a 'ping' to get data from the server every n intervals of the update timer */
  private static final int SEND_PING_EVERY = 10;
  /**
   * Max watchdog counter value.
   * The chargenode timer will automatically stop if we don't get a 'keepalive' message.
   */
  private static final int WATCHDOG_OVERFLOW = 8;

  /** Message handler for update timer (sends data periodically) */
  private final Handler updateTimer = new Handler();

  private int connectionStatus = STATUS_OFFLINE;
  private int chargeStatus = STATUS_NOT_CHARGING;

  /**
   * This counter will increment on the timer.
   * If not reset (e.g. by Keep Alive message), the timer will stop itself.
   */
  private int watchdogCounter = 0;
  /**
   * This counter will increment on the timer.
   * When it reaches SEND_PING_EVERY, a PING is sent to get data from the server.
   */
  private int pingCounter = 0;

  private final WeakReference<Context> weakContext;

  /** Used to keep track of cookie data returned by the server, so we can keep a session going. */
  private Bundle cookieData = new Bundle();

  /** Used to maintain a list of queued HTTP requests. */
  private Queue<ChargerHTTPConn> requestQueue = new LinkedList<ChargerHTTPConn>();


  public ChargeNode(Context context)
  {
    dashMessages = new DashMessages(context,this,CHARGE_NODE_INTENT);
    weakContext = new WeakReference<Context>(context);
    resume();      // Start the update timer!
  }

  /**
   * Suspend should be called when the UI is paused,
   * to stop timers, etc.
   */
  public void suspend()
  {
    chargeStatus = STATUS_NOT_CHARGING;
    connectionStatus = STATUS_OFFLINE;
    timerStop(); // ... Suspends the update timer.
  }

  /**
   * Resume should be called when the UI is reloaded.
   */
  public void resume()
  {
    //chargeStatus = STATUS_NOT_CHARGING;
    //connectionStatus = STATUS_OFFLINE;
    timerStart(); // ... Restarts the update timer.
  }

  /**
   * Returns the status of the ChargeNode connection, so other parts of the application
   * can decide what to do.
   * (I.e. whether to reset the charge node UI page)
   */
  public int getConnectionStatus()
  {
    return connectionStatus;
  }

  /**
   * Returns the status of the ChargeNode, so other parts of the application
   * can decide what to do.
   * (I.e. whether to reset the charge node UI page)
   */
  public int getChargeStatus()
  {
    return chargeStatus;
  }






  /**
   * Intent Processor.
   */
  public void messageReceived(String action, int message, Float floatData, String stringData, Bundle data)
  {
    // --DEBUG!--Log.i(com.tumanako.ui.UIActivity.APP_TAG, String.format( " ChargeNode -> Msg Rec: %d", message) );

    // Message received! Reset the watchdog counter.
    watchdogCounter = 0;

    // Check to see if any cookie data have been received (e.g. from a web server):
    if ( (data != null) && (data.containsKey("Cookies")) ) {
      Bundle tempCookies = data.getBundle("Cookies");
      if (!tempCookies.isEmpty()) {
        cookieData = new Bundle(tempCookies);
      }
    }

    switch (message) {

      // ************** Messages from the UI: ****************************
      case CHARGE_NODE_CONNECT:
        // This is actually Connect OR Disconnect (depending on current status).
        if (connectionStatus == STATUS_CONNECTED) {
          // Already connected. Need to disconnect:
          doChargeSet(0);  // Turn off the charger if it's on.
          connectionStatus = STATUS_OFFLINE;
          dashMessages.sendData( UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null,CHARGE_NODE_DEFAULT_HTML,makeChargeData(STATUS_OFFLINE,STATUS_NOT_CHARGING,0f,0f) );   // Tell the UI we have disconnected.
        } else {
          // Need to connect!
          if ( (data != null) && (data.containsKey("j_username")) && (data.containsKey("j_password")) ) {
            // A bundle containing 'USER' and 'PASSWORD' strings must be supplied to connect.
            dashMessages.sendData( UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null,CHARGE_NODE_CONNECT_HTML,makeChargeData(STATUS_OFFLINE,STATUS_NOT_CHARGING,0f,0f) );   // Clear old UI data.
            doLogin(data);
            connectionStatus = STATUS_CONNECTING;
            timerStart();    // Make sure the update timer is running!
          }
        }
        break;

      case CHARGE_NODE_CHARGESTART:
        if (connectionStatus == STATUS_CONNECTED) doChargeSet(1); // Turn ON the charger!
        break;

      case CHARGE_NODE_CHARGESTOP:
        if (connectionStatus == STATUS_CONNECTED) doChargeSet(0); // Turn off the charger!
        break;

      // ************** Messages from the Comm thread ****************************
      case CHARGE_NODE_HTML_DATA:
        // HTTP response received from server.
        if ((connectionStatus == STATUS_CONNECTING) && (data != null) && (data.containsKey("ResponseCode"))) {
          // --DEBUG!--
          Log.d(com.tumanako.ui.UIActivity.APP_TAG, String.format( " ChargeNode -> HTTP Response Code: %d", data.getInt("ResponseCode")) );
          // Check the r4esponse code. Should be 200 if we logged in OK:
          if (data.getInt("ResponseCode", 999) == 200) {
            // Connected OK!
            connectionStatus = STATUS_CONNECTED;
            dashMessages.sendData(UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null, CHARGE_NODE_CONNECTED_HTML, makeChargeData(connectionStatus, chargeStatus, 0.0f, 0.0f));
            timerStart();   // Make sure timer is running (for PING updates).
          } else {
            // Login error.
            connectionStatus = STATUS_OFFLINE;
            chargeStatus = STATUS_NOT_CHARGING;
            dashMessages.sendData(UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null, CHARGE_NODE_LOGINERROR_HTML, makeChargeData(connectionStatus, chargeStatus, 0.0f, 0.0f));
            timerStop();
          }
        }
        //serverPage = new String(stringData);
        //dashMessages.sendData( UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null, serverPage, getChargeData(STATUS_CONNECTED,0.0f,0.0f) );
        break;

      case CHARGE_NODE_JSON_DATA:
        if (connectionStatus == STATUS_CONNECTED) {
          Log.i(com.tumanako.ui.UIActivity.APP_TAG, String.format( " ChargeNode -> HTTP Response Code: %d", data.getInt("ResponseCode")) );
          // The text we received back should be JSON data.
          // Parse: (note Try / Catch block to catch JSON errors, in case data can't be parsed).
          try {
            JSONObject jsonReceived = new JSONObject(stringData);  // Parse the JSON data
            //--DEBUG!!-- Log.i("HTTPConn", String.format("JSON Parsed. %d entries in data section.", jsonDataSection.length() ) );
            // The JSON data should contain 3 sections:
            //   "datumQueryCommand" = JSONData: some name/value pairs with meta data
            //   "data"              = JSONArray: An array of JSON objects containing info about the items at
            //                         the solar node (i.e. switches). This is the data we are interested in.
            //   "tz"                = TIme zone description.
            JSONArray jsonDataSection = jsonReceived.getJSONArray("data");  // Get the "data" array.
            // Search the data array for an entry with sourceId = /power/switch/1:
            for (int i=0; i<jsonDataSection.length(); i++) {
              JSONObject jsonDataItem = jsonDataSection.getJSONObject(i);
              if ((jsonDataItem.has("sourceId")) && (jsonDataItem.getString("sourceId").equals("/power/switch/2"))) {
                Log.i("HTTPConn", "  FOUND!!! Value = " + String.format("%d", jsonDataItem.getInt("integerValue")));
                if (jsonDataItem.getInt("integerValue") == 1) {
                  // Charging!
                  chargeStatus = STATUS_CHARGING;
                } else {
                  chargeStatus = STATUS_NOT_CHARGING;
                }
                dashMessages.sendData( UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null, CHARGE_NODE_OK_HTML, makeChargeData(connectionStatus,chargeStatus,0.0f,0.0f) );
              }
              //--DEBUG!!--Log.i("HTTPConn", "  sourceId: " + jsonDataItem.getString("sourceId") + "\n  integerValue: " + String.format("%d", jsonDataItem.getInt("integerValue") )  );
            }
          } catch (JSONException e) {
            Log.w(com.tumanako.ui.UIActivity.APP_TAG, e);
            //chargeStatus = STATUS_NOT_CHARGING;
            //connectionStatus = STATUS_OFFLINE;
            //dashMessages.sendData( UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null, "", makeChargeData(connectionStatus,chargeStatus,0.0f,0.0f) );
            Log.e(com.tumanako.ui.UIActivity.APP_TAG, "Error Parsing JSON Data!");
          }
        }
        break;
/*
      case ChargerHTTPConn.XML_DATA:
        Log.d(com.tumanako.ui.UIActivity.APP_TAG, " ChargeNode -> XML Data Message." );
        String chargeStatus = "";
        String pageHTML = "";
        float chargeCurrent = 0f;
        float chargeUnits = 0f;
        if (data != null) {
          if (data.containsKey("ChargeStatus")) chargeStatus  = data.getString("ChargeStatus");
          if (data.containsKey("Current"))      chargeCurrent = data.getFloat("Current", 0.0f);
          if (data.containsKey("Units"))        chargeUnits   = data.getFloat("Units", 0.0f);
          if (data.containsKey("PageData"))     pageHTML      = data.getString("PageData");
        }
        if (chargeStatus.equals("CHARGING"))  status = STATUS_CHARGING;
        else                                  status = STATUS_CONNECTED;
        dashMessages.sendData( UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null, pageHTML, getChargeData(status,chargeCurrent,chargeUnits) );
        break;
*/

      case ChargerHTTPConn.CONN_ERROR:
        //chargeStatus = STATUS_NOT_CHARGING;
        //connectionStatus = STATUS_OFFLINE;
        //dashMessages.sendData( UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null, "", makeChargeData(connectionStatus,chargeStatus,0.0f,0.0f) );
        break;
    }
  }

  /** Suspends the update timer. */
  private void timerStop()
  {
    updateTimer.removeCallbacks(updateTimerTask);
  }

  /** Restarts the update timer. */
  private void timerStart()
  {
    updateTimer.postDelayed(updateTimerTask, UPDATE_TIME);
  }

  /** Creates a Runnable which will be called after a delay, to carry out a read of vehicle data. */
  private final Runnable updateTimerTask = new Runnable()
    {
    @Override
    public void run()
    {
      timerStop();  // Clears existing timers.
      //--DEBUG!!-- Log.i(com.tumanako.ui.UIActivity.APP_TAG, " ChargeNode -> Timer" );
      watchdogCounter++;
      if (watchdogCounter >= WATCHDOG_OVERFLOW) {
        // Nothing has happend for a while! Stop the timer and set status to 'Disconnected'.
        chargeStatus = STATUS_NOT_CHARGING;
        connectionStatus = STATUS_OFFLINE;  // Give up.
        Log.i(com.tumanako.ui.UIActivity.APP_TAG, " ChargeNode -> Watchdog Overflow. Stopping. ");
      } else {
        /***********************************************************************************************
        * HTTP Request Queue Management:
        *
        * Check the status of the first object in the HTTP request queue.
        *  - If it hasn't started, start it.
        *  - If it's running, do nothing.
        *  - If it's finished, throw it away and start the next one!
        */
        if ((requestQueue != null) && (!requestQueue.isEmpty())) {
          // There is at least one HTTP request in the queue:
          ChargerHTTPConn currentConn = requestQueue.peek(); // Get reference to current (first) item in the queue.
          if (!currentConn.isAlive()) {
            // Current item is not "Alive" (i.e. running). Has it started yet?
            if (currentConn.isRun()) {
              //--DEBUG!!--- Log.i(com.tumanako.ui.UIActivity.APP_TAG, " ChargeNode -> Thread Finished. Removing From Queue. " );
              // The thread has run, so it must have finished!
              currentConn = null;
              requestQueue.poll();   // Removes the item from the queue.
              if (!requestQueue.isEmpty()) currentConn = requestQueue.peek(); // Get reference to next item in the queue (if available)
            }
            // NOW: If we still have a valid HTTP connection item from the queue, we know it needs to be started.
            if (currentConn != null) {
              //--DEBUG!!--- Log.i(com.tumanako.ui.UIActivity.APP_TAG, " ChargeNode -> Starting HTTP Conn From Queue. " );
              currentConn.run();
            }
          }
        }
        // *****************************************************************************************

        switch (connectionStatus) {
          case STATUS_CONNECTING:
            // We are waiting for a response from the server:
            break;

          case STATUS_CONNECTED:
            // We are connected: Add to the Ping counter, and send a 'PING' if enough time has passed:
            pingCounter++;
            if (pingCounter >= SEND_PING_EVERY) {
              pingCounter = 0;
              Log.d(com.tumanako.ui.UIActivity.APP_TAG, " ChargeNode -> Ping! ");
              dashMessages.sendData(UIActivity.UI_INTENT_IN, IDashMessages.CHARGE_NODE_ID, null, CHARGE_NODE_UPDATING_HTML, makeChargeData(connectionStatus, chargeStatus, 0.0f, 0.0f));
              requestQueue.add(new ChargerHTTPConn(weakContext,CHARGE_NODE_INTENT,PING_URL,null,cookieData,false,CHARGE_NODE_JSON_DATA));
            }
            break;

          default:
        }
        if (connectionStatus != STATUS_OFFLINE) timerStart(); // Restarts the timer.
      }
    }
  };
  // ***********************************************************************************************

  // ********** Controls ************************************************************

  /** Login */
  private void doLogin(Bundle dataReceived)
  {
    requestQueue.add(new ChargerHTTPConn(
        weakContext,
        CHARGE_NODE_INTENT,
        LOGIN_URL,
        dataReceived, // Username and password are supplied as the 'Post Data' for the HTTP connection.
        cookieData,
        false,
        CHARGE_NODE_HTML_DATA));
  }



  /** Charge Start / Stop */
  private void doChargeSet(int newSetting)
  {
    requestQueue.add(new ChargerHTTPConn(
        weakContext,
        CHARGE_NODE_INTENT,
        "http://data.solarnetwork.net/solarreg/u/instr/add.json",
        makeChargeControl(newSetting),
        cookieData,
        false,
        CHARGE_NODE_HTML_DATA));
  }

  private Bundle makeChargeData(int conStatus, int chgStatus, float current, float ah)
  {
    // This makes a bundle of values containing the charge data:
    // charge status, chrage current and charge amount (ah):
    Bundle chargeData = new Bundle();
    chargeData.putFloat(CONNECTION_STATUS, (conStatus==STATUS_CONNECTED) ? 1.0f : 0.0f);
    chargeData.putFloat(CHARGE_STATUS,     (chgStatus==STATUS_CHARGING)  ? 1.0f : 0.0f);
    chargeData.putFloat(CHARGE_CURRENT,    current);
    chargeData.putFloat(CHARGE_AH,         ah);
    return chargeData;
  }

  private Bundle makeChargeControl(int setTo)
  {
    // This makes a bundle of data (name/ value pairs) which can be used in a
    // POST HTTP request to set the charger status (ON or OFF).
    //  setTo specifies the new status: 0 = OFF, 1 = ON.
    Bundle chargeData = new Bundle();
    chargeData.putString("nodeId",              "30");
    chargeData.putString("topic",               "SetControlParameter");
    chargeData.putString("parameters[0].name",  "/power/switch/2");
    chargeData.putString("parameters[0].value", String.format("%d", setTo));
    return chargeData;
  }
}
