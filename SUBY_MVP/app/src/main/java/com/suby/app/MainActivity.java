package com.suby.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
  private WebView web;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private void callback(String id, JSONObject data) {
    runOnUiThread(() -> {
      if (web != null) web.evaluateJavascript("window.onNativeAuthResult && window.onNativeAuthResult(" +
        JSONObject.quote(id) + "," + data.toString() + ");", null);
    });
  }

  public class SubyBridge {
    @JavascriptInterface public void openCall(String mode) {
      runOnUiThread(() -> {
        Intent i = new Intent(MainActivity.this, LiveCallActivity.class);
        i.putExtra("mode", mode);
        startActivity(i);
      });
    }

    @JavascriptInterface public void auth(String action, String name, String email, String password,
                                          String role, String callbackId) {
      executor.execute(() -> {
        JSONObject out = new JSONObject();
        try {
          FirebaseAuth auth = FirebaseAuth.getInstance();
          FirebaseFirestore db = FirebaseFirestore.getInstance();
          FirebaseUser user;
          if ("register".equals(action)) {
            user = Tasks.await(auth.createUserWithEmailAndPassword(email.trim(), password)).getUser();
            if (user == null) throw new IllegalStateException("Account create नहीं हुआ।");
            Tasks.await(user.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()));
            Tasks.await(user.sendEmailVerification());
            Map<String,Object> p = new HashMap<>();
            p.put("uid", user.getUid()); p.put("name", name.trim());
            p.put("email", email.trim().toLowerCase()); p.put("role", role);
            p.put("status", "host".equals(role) ? "pending" : "active");
            p.put("createdAt", System.currentTimeMillis());
            Tasks.await(db.collection("users").document(user.getUid()).set(p));
            out.put("ok", true); out.put("verificationSent", true);
            out.put("message", "Verification email भेज दी गई है। Gmail में लिंक खोलकर verify करें, फिर Login करें।");
          } else if ("login".equals(action)) {
            user = Tasks.await(auth.signInWithEmailAndPassword(email.trim(), password)).getUser();
            if (user == null) throw new IllegalStateException("Login नहीं हुआ।");
            Tasks.await(user.reload()); user = auth.getCurrentUser();
            if (user == null || !user.isEmailVerified()) {
              if (user != null) Tasks.await(user.sendEmailVerification());
              throw new IllegalStateException("पहले Gmail में आया verification link खोलें। नया link भेज दिया गया है।");
            }
            Map<String,Object> p = Tasks.await(db.collection("users").document(user.getUid()).get()).getData();
            if (p == null) throw new IllegalStateException("Profile नहीं मिली।");
            out.put("ok", true); out.put("email", user.getEmail()); out.put("uid", user.getUid());
            out.put("name", String.valueOf(p.getOrDefault("name", user.getDisplayName())));
            out.put("role", String.valueOf(p.getOrDefault("role", "user")));
            out.put("status", String.valueOf(p.getOrDefault("status", "active")));
          } else if ("session".equals(action)) {
            user = auth.getCurrentUser();
            if (user == null) { out.put("ok", true); out.put("signedIn", false); }
            else {
              Tasks.await(user.reload()); user = auth.getCurrentUser();
              if (user == null || !user.isEmailVerified()) { out.put("ok", true); out.put("signedIn", false); }
              else {
                Map<String,Object> p = Tasks.await(db.collection("users").document(user.getUid()).get()).getData();
                if (p == null) throw new IllegalStateException("Profile नहीं मिली।");
                out.put("ok", true); out.put("signedIn", true); out.put("email", user.getEmail()); out.put("uid", user.getUid());
                out.put("name", String.valueOf(p.getOrDefault("name", user.getDisplayName())));
                out.put("role", String.valueOf(p.getOrDefault("role", "user")));
                out.put("status", String.valueOf(p.getOrDefault("status", "active")));
              }
            }
          } else if ("logout".equals(action)) {
            auth.signOut(); out.put("ok", true); out.put("signedIn", false);
          } else throw new IllegalArgumentException("Unknown action");
        } catch (Exception e) {
          String m = e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage() : e.getMessage();
          try { out.put("ok", false); out.put("message", m == null ? "कुछ गलत हुआ।" : m); } catch (Exception ignored) {}
        }
        callback(callbackId, out);
      });
    }
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    web = new WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.addJavascriptInterface(new SubyBridge(), "SubyNative");
    web.setWebViewClient(new WebViewClient());
    web.setWebChromeClient(new WebChromeClient());
    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }

  @Override public void onBackPressed() {
    if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
  }

  @Override protected void onDestroy() {
    executor.shutdown();
    super.onDestroy();
  }
}