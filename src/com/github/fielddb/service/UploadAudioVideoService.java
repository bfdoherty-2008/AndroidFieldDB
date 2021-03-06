package com.github.fielddb.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.security.KeyStore;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

import com.github.fielddb.BugReporter;
import com.github.fielddb.Config;
import com.github.fielddb.PrivateConstants;
import com.github.fielddb.datacollection.NotifyingIntentService;
import com.github.fielddb.datacollection.SecureHttpClient;
import com.github.fielddb.R;
import com.google.gson.JsonObject;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.util.Log;

/**
 * FIXME this needs to be updated to not use new http connections
 */
public class UploadAudioVideoService extends NotifyingIntentService {
  protected String mDeviceDetails = "{}";
  protected String mUsername = "default";

  public UploadAudioVideoService(String name) {
    super(name);
  }

  public UploadAudioVideoService() {
    super("UploadAudioVideoService");
  }

  @Override
  protected void onHandleIntent(Intent intent) {
    if (Config.D) {
      Log.d(Config.TAG, " we are in debug mode, not uploading audio/video file");
      return;
    }

    /* only upload files when connected to wifi */
    ConnectivityManager connManager = (ConnectivityManager) getApplicationContext().getSystemService(
        Context.CONNECTIVITY_SERVICE);
    NetworkInfo wifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
    if (!wifi.isConnected()) {
      Log.d(Config.TAG, " we are not using wifi, not uploading audio/video file");
      return;
    }

    /* only upload files with content */
    if (intent.getData() == null) {
      return;
    }
    Uri uri = intent.getData();
    if (uri.getPath() == null) {
      return;
    }
    File f = new File(uri.getPath());
    if (!f.exists()) {
      return;
    }
    if (f.length() < 5000) {
      Log.d(Config.TAG, "Not uploading, " + uri.getLastPathSegment() + " was too small " + f.length());
      return;
    }

    this.statusMessage = "Uploading audio video";
    this.tryAgain = intent;
    this.keystoreResourceId = R.raw.sslkeystore;
    if (Config.D) {
      Log.d(Config.TAG, "Inside UploadAudioVideoService intent");
    }
    BugReporter.putCustomData("action", "uploadAudioVideo:::");
    BugReporter.putCustomData("urlString", Config.DEFAULT_UPLOAD_AUDIO_VIDEO_URL);

    super.onHandleIntent(intent);

    if (!"".equals(this.userFriendlyErrorMessage)) {
      this.notifyUser(" " + this.userFriendlyErrorMessage, this.noti, this.notificationId, true);
      BugReporter.sendBugReport(this.userFriendlyErrorMessage);
      return;
    }

    if (intent.hasExtra(Config.EXTRA_PARTICIPANT_ID)) {
      mUsername = intent.getExtras().getString(Config.EXTRA_PARTICIPANT_ID);
    }
    if (intent.hasExtra(Config.EXTRA_EXPERIMENT_TRIAL_INFORMATION)) {
      mDeviceDetails = intent.getExtras().getString(Config.EXTRA_EXPERIMENT_TRIAL_INFORMATION);
    }

    String JSONResponse = this.upload(intent.getData());
    if (JSONResponse == null && "".equals(this.userFriendlyErrorMessage)) {
      this.userFriendlyErrorMessage = "Server response was missing. Please report this.";
    }
    if (!"".equals(this.userFriendlyErrorMessage)) {
      this.notifyUser(" " + this.userFriendlyErrorMessage, this.noti, this.notificationId, true);
      BugReporter.sendBugReport(this.userFriendlyErrorMessage);
      return;
    }

    processUploadResponse(intent.getData(), JSONResponse);
    if (!"".equals(this.userFriendlyErrorMessage)) {
      this.notifyUser(" " + this.userFriendlyErrorMessage, this.noti, this.notificationId, true);
      BugReporter.sendBugReport(this.userFriendlyErrorMessage);
      return;
    }

    /* all is well, get their cookie set */
    // this.getCouchCookie(username, generatedPassword,
    // Config.DEFAULT_DATA_LOGIN);

    /* Success: remove the notification */
    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(this.notificationId);
    com.github.fielddb.model.Activity.sendActivity("{\"uploaded\" : \"audio\"}", "{}",
        "*** Uploaded audio sucessfully ***");
  }

  public String upload(Uri uri) {
    String filePath = uri.getPath();
    this.statusMessage = "Uploading audio " + uri.getLastPathSegment();
    BugReporter.putCustomData("uploadAudio", uri.getLastPathSegment());
    String urlStringAuthenticationSession = Config.DEFAULT_UPLOAD_AUDIO_VIDEO_URL;

    /* Actually uploads the video */
    SecureHttpClient httpClient = new SecureHttpClient(getApplicationContext());
    httpClient.setKeystoreIdandPassword(R.raw.sslkeystore, PrivateConstants.KEYSTORE_PASS);
    // HttpClient httpClient = new DefaultHttpClient();

    HttpContext localContext = new BasicHttpContext();
    String url = Config.DEFAULT_UPLOAD_AUDIO_VIDEO_URL;
    HttpPost httpPost = new HttpPost(url);

    MultipartEntity entity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE, null, Charset.forName("UTF-8"));

    try {

      entity.addPart("videoFile", new FileBody(new File(filePath)));

      entity.addPart("token", new StringBody(Config.DEFAULT_UPLOAD_TOKEN, "text/plain", Charset.forName("UTF-8")));

      entity.addPart("username", new StringBody(mUsername, "text/plain", Charset.forName("UTF-8")));

      entity.addPart("dbname", new StringBody(Config.DEFAULT_CORPUS, "text/plain", Charset.forName("UTF-8")));

      entity.addPart("returnTextGrid", new StringBody("true", "text/plain", Charset.forName("UTF-8")));

    } catch (UnsupportedEncodingException e) {
      Log.d(Config.TAG, "Failed to add entity parts due to string encodinuserFriendlyMessageg UTF-8");
      e.printStackTrace();
    }

    httpPost.setEntity(entity);
    String userFriendlyErrorMessage = "";
    String JSONResponse = "";

    try {
      HttpResponse response = httpClient.execute(httpPost, localContext);
      BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), "UTF-8"));
      String newLine;
      do {
        newLine = reader.readLine();
        if (newLine != null) {
          JSONResponse += newLine;
        }
      } while (newLine != null);

    } catch (ClientProtocolException e1) {
      this.userFriendlyErrorMessage = "Problem using POST, please report this error.";
      e1.printStackTrace();
    } catch (IOException e1) {
      this.userFriendlyErrorMessage = "Problem opening upload connection to server, please report this error.";
      e1.printStackTrace();
    }

    if ("".equals(JSONResponse)) {
      this.userFriendlyErrorMessage = "Unknown error uploading data to server";
      return null;
    }

    if (!"".equals(this.userFriendlyErrorMessage)) {
      return null;
    }
    return JSONResponse;
  }

  public int processUploadResponse(Uri uri, String jsonResponse) {
    if (jsonResponse != null && Config.D) {
      Log.d(Config.TAG, jsonResponse);
    }
    JsonObject json = (JsonObject) NotifyingIntentService.jsonParser.parse(jsonResponse);
    if (json.has("userFriendlyErrors")) {
      this.userFriendlyErrorMessage = json.get("userFriendlyErrors").getAsString();
      return 0;
    }
    if (!json.has("files")) {
      this.userFriendlyErrorMessage = "The server response is very strange, please report this.";
      return 0;
    } else {
      com.github.fielddb.model.Activity.sendActivity("uploadAudio", jsonResponse);
    }

    return 0;
  }
}
