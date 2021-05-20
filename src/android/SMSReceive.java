package com.andreszs.cordova.sms;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.SmsMessage;
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.Date;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SMSReceive extends CordovaPlugin {
	private static final String LOG_TAG = "cordova-plugin-sms-receive";
	private static final String ACTION_START_WATCH = "startWatch";
	private static final String ACTION_STOP_WATCH = "stopWatch";
	private static final String SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED";
	private static final String MMS_RECEIVED_ACTION = "android.provider.Telephony.WAP_PUSH_RECEIVED";
	private static final String MMS_DATA_TYPE = "application/vnd.wap.mms-message";

	public static final int START_WATCH_REQ_CODE = 0;
	public static final int PERMISSION_DENIED_ERROR = 20;

	private BroadcastReceiver smsReceiver = null;
	private BroadcastReceiver mmsReceiver = null;

	private JSONArray requestArgs;
	private CallbackContext callbackContext;

	public boolean execute(String action, JSONArray inputs, CallbackContext callbackContext) throws JSONException {
		PluginResult result = null;
		this.callbackContext = callbackContext;
		this.requestArgs = inputs;
		if (action.equals(ACTION_START_WATCH)) {
			if(!hasPermission()) {
				requestPermissions(START_WATCH_REQ_CODE);
			} else {
				result = this.startWatch(callbackContext);
			}
		} else if (action.equals(ACTION_STOP_WATCH)) {
			result = this.stopWatch(callbackContext);
		} else {
			Log.d(LOG_TAG, String.format("Invalid action passed: %s", action));
			result = new PluginResult(PluginResult.Status.INVALID_ACTION);
		}
		if (result != null) {
			callbackContext.sendPluginResult(result);
		}
		return true;
	}

	public void onDestroy() {
		this.stopWatch(null);
	}

	private PluginResult startWatch(CallbackContext callbackContext) {
		Log.d(LOG_TAG, ACTION_START_WATCH);
		if (this.smsReceiver == null) {
			this.createIncomingSMSReceiver();
		}
		if (this.mmsReceiver == null) {
			this.createIncomingMMSReceiver();
		}
		if (callbackContext != null) {
			callbackContext.success();
		}
		return null;
	}

	private PluginResult stopWatch(CallbackContext callbackContext) {
		Log.d(LOG_TAG, ACTION_STOP_WATCH);
		if (this.smsReceiver != null) {
			try {
				webView.getContext().unregisterReceiver(this.smsReceiver);
			} catch (Exception e) {
				Log.d(LOG_TAG, "error unregistering network receiver: " + e.getMessage());
			} finally {
				this.smsReceiver = null;
			}
		}
		if (this.mmsReceiver != null) {
			try {
				webView.getContext().unregisterReceiver(this.mmsReceiver);
			} catch (Exception e) {
				Log.d(LOG_TAG, "error unregistering network receiver: " + e.getMessage());
			} finally {
				this.mmsReceiver = null;
			}
		}
		if (callbackContext != null) {
			callbackContext.success();
		}
		return null;
	}

	private JSONObject getJsonFromCursor(Cursor cur) {
		JSONObject json = new JSONObject();
		int nCol = cur.getColumnCount();
		String keys[] = cur.getColumnNames();
		try {
			for (int j=0; j<nCol; j++) {
				switch(cur.getType(j)) {
					case Cursor.FIELD_TYPE_NULL:
						json.put(keys[j], JSONObject.NULL);
						break;
					case Cursor.FIELD_TYPE_INTEGER:
						json.put(keys[j], cur.getLong(j));
						break;
					case Cursor.FIELD_TYPE_FLOAT:
						json.put(keys[j], cur.getFloat(j));
						break;
					case Cursor.FIELD_TYPE_STRING:
						json.put(keys[j], cur.getString(j));
						break;
					case Cursor.FIELD_TYPE_BLOB:
						json.put(keys[j], cur.getBlob(j));
						break;
				}
			}
		}
		catch (Exception e) {
			return null;
		}
		return json;
	}

	private void onSMSArrive(JSONObject json) {
		webView.loadUrl("javascript:try{cordova.fireDocumentEvent('onSMSArrive', {'data': "+json+"});}catch(e){console.log('exception firing onSMSArrive event from native');};");
	}

	protected void createIncomingSMSReceiver() {
		this.smsReceiver = new BroadcastReceiver() {
			public void onReceive(Context context, Intent intent) {
				if (intent.getAction().equals(SMS_RECEIVED_ACTION)) {
					// Create SMS container
					SmsMessage smsmsg = null;
					// Determine which API to use
					if (Build.VERSION.SDK_INT >= 19) {
						try {
							SmsMessage[] sms = Telephony.Sms.Intents.getMessagesFromIntent(intent);
							smsmsg = sms[0];
						} catch (Exception e) {
							Log.d(LOG_TAG, e.getMessage());
						}
					} else {
						Bundle bundle = intent.getExtras();
						Object pdus[] = (Object[]) bundle.get("pdus");
						try {
							smsmsg = SmsMessage.createFromPdu((byte[]) pdus[0]);
						} catch (Exception e) {
							Log.d(LOG_TAG, e.getMessage());
						}
					}
					// Get SMS contents as JSON
					if(smsmsg != null) {
						JSONObject jsms = SMSReceive.this.getJsonFromSmsMessage(smsmsg);
						SMSReceive.this.onSMSArrive(jsms);
						Log.d(LOG_TAG, jsms.toString());
					}else{
						Log.d(LOG_TAG, "smsmsg is null");
					}
				}
			}
		};
		IntentFilter filter = new IntentFilter(SMS_RECEIVED_ACTION);
		try {
			webView.getContext().registerReceiver(this.smsReceiver, filter);
		} catch (Exception e) {
			Log.d(LOG_TAG, "error registering broadcast receiver: " + e.getMessage());
		}
	}

	protected void createIncomingMMSReceiver() {
		this.mmsReceiver = new BroadcastReceiver() {
			public void onReceive(Context context, Intent intent) {

				if (intent.getAction().equals(MMS_RECEIVED_ACTION)) {
					JSONObject jsms = getJsonFromMmsMessage();
					SMSReceive.this.onSMSArrive(jsms);
					Log.d(LOG_TAG, jsms.toString());
				}
			}
		};
		IntentFilter filter = new IntentFilter();
		try {
			filter.addAction(MMS_RECEIVED_ACTION);
			filter.addDataType(MMS_DATA_TYPE);
			webView.getContext().registerReceiver(this.mmsReceiver, filter);
		} catch (Exception e) {
			Log.d(LOG_TAG, "error registering broadcast receiver: " + e.getMessage());
		}
	}

	private JSONObject getJsonFromSmsMessage(SmsMessage sms) {
		JSONObject json = new JSONObject();
		try {
			json.put( "address", sms.getOriginatingAddress() );
			json.put( "body", sms.getMessageBody() ); // May need sms.getMessageBody.toString()
			json.put( "date_sent", sms.getTimestampMillis() );
			json.put( "date", System.currentTimeMillis() );
			json.put( "service_center", sms.getServiceCenterAddress());
		}
		catch (Exception e) {
			Log.d(LOG_TAG, e.getMessage());
		}
		return json;
	}

	private JSONObject getJsonFromMmsMessage() {
		JSONObject json = new JSONObject();
		try {
			// 수신된 MMS 중 가장 최근 메시지의 id를 가져온다.
			ContentResolver contentResolver = this.cordova.getContext().getContentResolver();
			final String[] projection = new String[]{"_id"};
			Uri uri = Uri.parse("content://mms");
			Cursor cursor = contentResolver.query(uri, projection, null, null, "_id desc limit 1");

			// 데이터가 존재하지 않는 경우
			if (cursor.getCount() == 0) {
				cursor.close();
				return null;
			}

			// 제일 처음 레코드로 이동하여 아이디를 가져온다.
			cursor.moveToFirst();
			String id = cursor.getString(cursor.getColumnIndex("_id"));
			cursor.close();

			String number = getAddressNumberFromMMS(id);
			String msg = getMessageFromMMS(id);

			json.put( "address", number );
			json.put( "body", msg ); // May need sms.getMessageBody.toString()
			json.put( "date_sent", System.currentTimeMillis() );
			json.put( "date", System.currentTimeMillis() );
			json.put( "service_center", "");
		}
		catch (Exception e) {
			Log.d(LOG_TAG, e.getMessage());
		}
		return json;
	}

	private String getAddressNumberFromMMS(String id) {
		String selection = new String("msg_id=" + id);
		String uriStr = MessageFormat.format("content://mms/{0}/addr", id);
		Uri uriAddress = Uri.parse(uriStr);

		ContentResolver contentResolver = this.cordova.getContext().getContentResolver();
		Cursor cAdd = contentResolver.query(uriAddress, new String[] { "address" }, selection, null, null);
		String name = null;
		if (cAdd.moveToFirst()) {
			do {
				String number = cAdd.getString(cAdd.getColumnIndex("address"));
				if (number != null) {
					try {
						Long.parseLong(number.replace("-", ""));
						name = number;
					} catch (NumberFormatException nfe) {
						if (name == null) {
							name = number;
						}
					}
				}
			} while (cAdd.moveToNext());
		}
		if (cAdd != null) {
			cAdd.close();
		}
		return name;
	}

	private String getMessageFromMMS(String id)
	{
		String result = "";

		String selectionPart = "mid=" + id;
		Uri uri = Uri.parse("content://mms/part");
		ContentResolver contentResolver = this.cordova.getContext().getContentResolver();
		Cursor cursor = contentResolver.query(uri, null, selectionPart, null, null);
		if (cursor.moveToFirst()) {
			do {
				String partId = cursor.getString(cursor.getColumnIndex("_id"));
				String type = cursor.getString(cursor.getColumnIndex("ct"));
				if ("text/plain".equals(type)) {
					String data = cursor.getString(cursor.getColumnIndex("_data"));
					String body;
					if (data != null) {
						result = parseMessageWithPartId(partId);
					} else {
						result = cursor.getString(cursor.getColumnIndex("text"));
					}

					break;
				}
			} while (cursor.moveToNext());
		}

		return result;
	}

	private String parseMessageWithPartId(String id)
	{
		Uri partURI = Uri.parse("content://mms/part/" + id);
		InputStream is = null;
		StringBuilder sb = new StringBuilder();
		try {
			ContentResolver contentResolver = this.cordova.getContext().getContentResolver();
			is = contentResolver.openInputStream(partURI);
			if (is != null) {
				InputStreamReader isr = new InputStreamReader(is, "UTF-8");
				BufferedReader reader = new BufferedReader(isr);
				String temp = reader.readLine();
				while (temp != null) {
					sb.append(temp);
					temp = reader.readLine();
				}
			}
		} catch (IOException e) {}
		finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {}
			}
		}
		return sb.toString();
	}

	/**
	 * Check if we have been granted SMS receiving permission on Android 6+
	 */
	private boolean hasPermission() {

		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return true;
		}

		if (cordova.getActivity().checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_DENIED) {
			return false;
		}

		if (cordova.getActivity().checkSelfPermission(Manifest.permission.RECEIVE_MMS) == PackageManager.PERMISSION_DENIED) {
			return false;
		}

		return true;

	}

	/**
	 * We override this so that we can access the permissions variable, which no longer exists in
	 * the parent class, since we can't initialize it reliably in the constructor!
	 *
	 * @param requestCode The code to get request action
	 */
	public void requestPermissions(int requestCode) {

		cordova.requestPermission(this, requestCode, Manifest.permission.RECEIVE_SMS);

	}

	/**
	 * processes the result of permission request
	 *
	 * @param requestCode The code to get request action
	 * @param permissions The collection of permissions
	 * @param grantResults The result of grant
	 */
	public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
		PluginResult result;
		for (int r : grantResults) {
			if (r == PackageManager.PERMISSION_DENIED) {
				Log.d(LOG_TAG, "Permission Denied!");
				result = new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION);
				callbackContext.sendPluginResult(result);
				return;
			}
		}
		switch(requestCode) {
			case START_WATCH_REQ_CODE:
				this.startWatch(this.callbackContext);
				break;
		}
	}

}