package com.github.fielddb;

/* https://github.com/ACRA/acralyzer/wiki/setup */
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Locale;

import org.acra.ACRA;
import org.acra.ACRAConfiguration;
import org.acra.annotation.ReportsCrashes;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.github.fielddb.database.FieldDBUserContentProvider;
import com.github.fielddb.database.UserContentProvider.UserTable;
import com.github.fielddb.model.User;
import com.github.fielddb.service.RegisterUserService;

/**
 * If you want the app to automatically update sample data if the user has wifi,
 * you can provide a mUpdateSampleData; If you want the app to always work
 * offline (no online sample data) you can provide mOfflineSampleData;
 * 
 * - mUpdateSampleData to be an intent which knows how to download/update sample
 * data from a server - mOfflineSampleData to have 1 sample datum
 * 
 */
@ReportsCrashes(formKey = "", formUri = "", reportType = org.acra.sender.HttpSender.Type.JSON, httpMethod = org.acra.sender.HttpSender.Method.PUT, formUriBasicAuthLogin = "see_private_constants", formUriBasicAuthPassword = "see_private_constants")
public class FieldDBApplication extends Application {
  protected User mUser;
  protected Intent mUpdateSampleData;

  @Override
  public void onCreate() {
    super.onCreate();
    String language = forceLocale(Config.DATA_IS_ABOUT_LANGUAGE_ISO);
    Log.d(Config.TAG, "Forced the locale to " + language);

    // (new File(Config.DEFAULT_OUTPUT_DIRECTORY)).mkdirs();
    initBugReporter();
    initUser();

    if (mUpdateSampleData != null) {
      getApplicationContext().startService(mUpdateSampleData);
    }
  }

  protected boolean initBugReporter() {
    ACRAConfiguration config = ACRA.getNewDefaultConfig(this);
    config.setFormUri(Config.ACRA_SERVER_URL);
    config.setFormUriBasicAuthLogin(Config.ACRA_USER);
    config.setFormUriBasicAuthPassword(Config.ACRA_PASS);

    /* https://github.com/FieldDB/FieldDB/issues/1435 */
    boolean doesAcraSupportKeystoresWorkaroundForSNIMissingVirtualhosts = false;
    if (doesAcraSupportKeystoresWorkaroundForSNIMissingVirtualhosts) {

      // Get an instance of the Bouncy Castle KeyStore format
      KeyStore trusted;
      try {
        trusted = KeyStore.getInstance("BKS");
        // Get the raw resource, which contains the keystore with
        // your trusted certificates (root and any intermediate certs)
        InputStream in = getApplicationContext().getResources().openRawResource(R.raw.sslkeystore);
        try {
          // Initialize the keystore with the provided trusted
          // certificates
          // Also provide the password of the keystore
          trusted.load(in, Config.KEYSTORE_PASS.toCharArray());
          // TODO waiting for https://github.com/ACRA/acra/pull/132
          // config.setKeyStore(trusted);
        } catch (NoSuchAlgorithmException e) {
          e.printStackTrace();
        } catch (CertificateException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        } finally {
          try {
            in.close();
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      } catch (KeyStoreException e1) {
        e1.printStackTrace();
      }

    } else {
      // TODO waiting for https://github.com/ACRA/acra/pull/132
      config.setDisableSSLCertValidation(true);
    }

    ACRA.setConfig(config);
    if (BuildConfig.DEBUG) {
      // TODO unable to disable acra sharedPreferencesName = "kartuliacra",
      // sharedPreferencesMode = Context.MODE_PRIVATE,
      // https://github.com/ACRA/acra/wiki/AdvancedUsage#letting-your-users-control-acra
      // SharedPreferences prefs = this.getSharedPreferences("kartuliacra",
      // Context.MODE_PRIVATE);
      // SharedPreferences.Editor editor = prefs.edit();
      // editor.putBoolean(ACRA.PREF_ENABLE_ACRA, false);
      // editor.commit();
      return false;
    } else {
      ACRA.init(this);
      return true;
    }
  }

  @SuppressLint("NewApi")
  protected boolean initUser() {
    // Get the user from the db
    String[] userProjection = { UserTable.COLUMN_ID, UserTable.COLUMN_REV, UserTable.COLUMN_USERNAME,
        UserTable.COLUMN_FIRSTNAME, UserTable.COLUMN_LASTNAME, UserTable.COLUMN_EMAIL, UserTable.COLUMN_GRAVATAR,
        UserTable.COLUMN_AFFILIATION, UserTable.COLUMN_RESEARCH_INTEREST, UserTable.COLUMN_DESCRIPTION,
        UserTable.COLUMN_SUBTITLE };
    CursorLoader cursorLoader = new CursorLoader(getApplicationContext(), FieldDBUserContentProvider.CONTENT_URI,
        userProjection, null, null, null);
    Cursor cursor = cursorLoader.loadInBackground();
    if (cursor == null) {
      Log.e(Config.TAG, "The user cursor is null, why did this happen?");
      BugReporter.sendBugReport("*** userCursor is null ***");
      return false;
    }
    cursor.moveToFirst();
    String _id = "";
    String username = "default";
    if (cursor.getCount() > 0) {
      _id = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_ID));
      String _rev = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_REV));
      username = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_USERNAME));
      String firstname = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_FIRSTNAME));
      String lastname = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_LASTNAME));
      String email = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_EMAIL));
      String gravatar = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_GRAVATAR));
      String affiliation = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_AFFILIATION));
      String researchInterest = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_RESEARCH_INTEREST));
      String description = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_DESCRIPTION));
      String subtitle = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_SUBTITLE));
      String actualJSON = "";
      mUser = new User(_id, _rev, username, firstname, lastname, email, gravatar, affiliation, researchInterest,
          description, subtitle, null, actualJSON);
      BugReporter.putCustomData("username", username);
      Config.CURRENT_USERNAME = username;
    } else {
      Log.e(Config.TAG, "There is no user... this is a problem the app wont work.");
      BugReporter.putCustomData("username", "unknown");
    }
    /* Make the default corpus point to the user's own corpus */
    Config.DEFAULT_CORPUS = Config.DEFAULT_CORPUS.replace("username", username);
    Config.CURRENT_USERNAME = username;
    Config.DEFAULT_OUTPUT_DIRECTORY = "/sdcard/" + Config.DATA_IS_ABOUT_LANGUAGE_NAME_ASCII + "-" + Config.APP_TYPE
        + "/" + Config.DEFAULT_CORPUS;
    (new File(Config.DEFAULT_OUTPUT_DIRECTORY)).mkdirs();

    BugReporter.putCustomData("dbname", Config.DEFAULT_CORPUS);

    Log.d(Config.TAG, cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_USERNAME)));
    cursor.close();

    if (mUser.get_rev() == null || "".equals(mUser.get_rev())) {
      Intent registerUser = new Intent(getApplicationContext(), RegisterUserService.class);
      registerUser.setData(Uri.parse(FieldDBUserContentProvider.CONTENT_URI + "/" + _id));
      getApplicationContext().startService(registerUser);
    }

    return mUser != null;
  }

  /**
   * Forces the locale for the duration of the app to the language needed for
   * that version of the Experiment. It accepts a variable in the form en or
   * en-US containing just the language code, or the language code followed by a
   * - and the co
   * 
   * @param lang
   * @return
   */
  public String forceLocale(String lang) {
    if (lang.equals(Locale.getDefault().getLanguage())) {
      return Locale.getDefault().getDisplayLanguage();
    }
    Configuration config = this.getBaseContext().getResources().getConfiguration();
    Locale locale;
    if (lang.contains("-")) {
      String[] langCountrycode = lang.split("-");
      locale = new Locale(langCountrycode[0], langCountrycode[1]);
    } else {
      locale = new Locale(lang);
    }
    Locale.setDefault(locale);
    config.locale = locale;
    this.getBaseContext().getResources()
        .updateConfiguration(config, this.getBaseContext().getResources().getDisplayMetrics());

    return Locale.getDefault().getDisplayLanguage();
  }
}
