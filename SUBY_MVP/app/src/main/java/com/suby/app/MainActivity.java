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
    @JavascriptInterface public void openCall(String mode, String hostUid) {
      runOnUiThread(() -> {
        Intent i = new Intent(MainActivity.this, LiveCallActivity.class);
        i.putExtra("mode", mode);
        i.putExtra("hostUid", hostUid == null ? "" : hostUid);
        startActivity(i);
      });
    }

    @JavascriptInterface public void auth(String action, String name, String email, String password,
                                          String role, String callbackId, String phone) {
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
            p.put("email", email.trim().toLowerCase()); p.put("phone", phone == null ? "" : phone.trim()); p.put("role", role);
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
            out.put("ok", true); out.put("email", user.getEmail()); out.put("uid", user.getUid()); out.put("phone", String.valueOf(p.getOrDefault("phone", "")));
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
                out.put("ok", true); out.put("signedIn", true); out.put("email", user.getEmail()); out.put("uid", user.getUid()); out.put("phone", String.valueOf(p.getOrDefault("phone", "")));
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

  public class SubySupportBridge {
    @JavascriptInterface public void support(String category, String message, String callbackId) {
      executor.execute(() -> {
        JSONObject out = new JSONObject();
        try {
          FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
          if (user == null) throw new IllegalStateException("पहले Login करें।");
          if (message == null || message.trim().length() < 10) throw new IllegalArgumentException("समस्या विस्तार से लिखें।");
          Map<String,Object> ticket = new HashMap<>();
          ticket.put("uid", user.getUid()); ticket.put("email", user.getEmail());
          ticket.put("category", category == null ? "Other" : category);
          ticket.put("message", message.trim()); ticket.put("status", "open");
          ticket.put("createdAt", System.currentTimeMillis());
          String id = FirebaseFirestore.getInstance().collection("supportTickets").document().getId();
          Tasks.await(FirebaseFirestore.getInstance().collection("supportTickets").document(id).set(ticket));
          out.put("ok", true); out.put("ticketId", id);
        } catch (Exception e) {
          String m = e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage() : e.getMessage();
          try { out.put("ok", false); out.put("message", m == null ? "Support request नहीं भेजी जा सकी।" : m); } catch (Exception ignored) {}
        }
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.onNativeSupportResult && window.onNativeSupportResult(" + out.toString() + ");", null); });
      });
    }

    @JavascriptInterface public void listHosts(String callbackId) {
      executor.execute(() -> {
        JSONObject out = new JSONObject();
        try {
          com.google.firebase.firestore.QuerySnapshot snap = Tasks.await(
            FirebaseFirestore.getInstance().collection("users").whereEqualTo("role", "host").whereEqualTo("status", "active").limit(50).get());
          org.json.JSONArray items = new org.json.JSONArray();
          for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
            JSONObject item = new JSONObject();
            item.put("uid", doc.getId());
            item.put("name", String.valueOf(doc.getString("name") == null ? "SUBY Host" : doc.getString("name")));
            items.put(item);
          }
          out.put("ok", true); out.put("hosts", items);
        } catch (Exception e) {
          try { out.put("ok", false); out.put("message", e.getMessage()); } catch (Exception ignored) {}
        }
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.onNativeHostsResult && window.onNativeHostsResult(" + out.toString() + ");", null); });
      });
    }

    @JavascriptInterface public void history(String callbackId) {
      executor.execute(() -> {
        JSONObject out = new JSONObject();
        try {
          FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
          if (user == null) throw new IllegalStateException("पहले Login करें।");
          org.json.JSONArray items = new org.json.JSONArray();
          String[] collections = {"rechargeRequests", "giftTransactions", "withdrawals"};
          for (String collection : collections) {
            com.google.firebase.firestore.QuerySnapshot snap = Tasks.await(
              FirebaseFirestore.getInstance().collection(collection).whereEqualTo("uid", user.getUid()).limit(50).get());
            for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
              Map<String,Object> data = doc.getData();
              if (data == null) continue;
              JSONObject item = new JSONObject();
              item.put("type", collection); item.put("amount", data.get("amount") == null ? 0 : data.get("amount"));
              item.put("status", data.get("status") == null ? "Recorded" : String.valueOf(data.get("status")));
              items.put(item);
            }
          }
          out.put("ok", true); out.put("items", items);
        } catch (Exception e) {
          String m = e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage() : e.getMessage();
          try { out.put("ok", false); out.put("message", m == null ? "History load नहीं हुई।" : m); } catch (Exception ignored) {}
        }
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.onNativeHistoryResult && window.onNativeHistoryResult(" + out.toString() + ");", null); });
      });
    }
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    web = new WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.addJavascriptInterface(new SubyBridge(), "SubyNative");
    web.addJavascriptInterface(new SubySupportBridge(), "SubyNativeSupport");
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