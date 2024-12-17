package net.kdt.pojavlaunch.authenticator.microsoft;

import android.content.Context;
import android.util.ArrayMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.feature.accounts.AccountType;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.task.TaskExecutors;
import com.movtery.zalithlauncher.utils.path.UrlManager;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Allow to perform a background login on a given account */
// TODO handle connection errors !
public class MicrosoftBackgroundLogin {
    private static final String authTokenUrl = "https://login.live.com/oauth20_token.srf";
    private static final String xblAuthUrl = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String xstsAuthUrl = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String mcLoginUrl = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String mcProfileUrl = "https://api.minecraftservices.com/minecraft/profile";
    private static final String mcStoreUrl = "https://api.minecraftservices.com/entitlements/mcstore";

    private final boolean mIsRefresh;
    private final String mAuthCode;
    private static final Map<Long, Integer> XSTS_ERRORS;
    static {
        XSTS_ERRORS = new ArrayMap<>();
        XSTS_ERRORS.put(2148916233L, R.string.xerr_no_account);
        XSTS_ERRORS.put(2148916235L, R.string.xerr_not_available);
        XSTS_ERRORS.put(2148916236L ,R.string.xerr_adult_verification);
        XSTS_ERRORS.put(2148916237L ,R.string.xerr_adult_verification);
        XSTS_ERRORS.put(2148916238L ,R.string.xerr_child);
        XSTS_ERRORS.put(2148074255L ,R.string.account_microsoft_xerr_unauthorized);
    }

    /* Fields used to fill the account  */
    public String msRefreshToken;
    public String mcName;
    public String mcToken;
    public String mcUuid;
    public boolean doesOwnGame;

    public MicrosoftBackgroundLogin(boolean isRefresh, String authCode){
        mIsRefresh = isRefresh;
        mAuthCode = authCode;
    }

    /** Performs a full login, calling back listeners appropriately  */
    public void performLogin(
            final Context context,
            final MinecraftAccount account,
            @Nullable final DoneListener doneListener,
            @Nullable final ErrorListener errorListener
    ) {
        Task.runTask(() -> {
            notifyProgress(1, context.getString(R.string.account_login_progress_access_token));
            String accessToken = acquireAccessToken(mIsRefresh, mAuthCode);
            notifyProgress(2, context.getString(R.string.account_login_progress_xbl_token));
            String xboxLiveToken = acquireXBLToken(accessToken);
            notifyProgress(3, context.getString(R.string.account_login_progress_xsts_token));
            String[] xsts = acquireXsts(xboxLiveToken);
            notifyProgress(4, context.getString(R.string.account_login_progress_minecraft_token));
            String mcToken = acquireMinecraftToken(xsts[0], xsts[1]);
            notifyProgress(5, context.getString(R.string.account_login_progress_checking));
            fetchOwnedItems(mcToken);
            checkMcProfile(mcToken);

            MinecraftAccount acc;
            if (account == null) {
                //尝试找到本地已经存在的，相同Profile UUID的账号
                MinecraftAccount acc1 = MinecraftAccount.loadFromProfileID(mcUuid);
                acc = acc1 != null ? acc1 : new MinecraftAccount();
            } else {
                acc = account;
            }

            if (doesOwnGame) {
                acc.xuid = xsts[0];
                acc.clientToken = "0"; /* FIXME */
                acc.accessToken = mcToken;
                acc.username = mcName;
                acc.profileId = mcUuid;
                acc.msaRefreshToken = msRefreshToken;
                acc.accountType = AccountType.MICROSOFT.getType();
                acc.updateMicrosoftSkin();
            }
            acc.save();
            Logging.i("Account", "Saved the account : " + acc.username);

            return acc;
        }).ended(TaskExecutors.getAndroidUI(), acc -> {
            if (doneListener != null && acc != null) doneListener.onLoginDone(acc);
        }).onThrowable(TaskExecutors.getAndroidUI(), e -> {
            Logging.e("MicroAuth", "Exception thrown during authentication", e);
            if(errorListener != null) errorListener.onLoginError(e);
        }).finallyTask(() -> {
            ProgressLayout.clearProgress(ProgressLayout.LOGIN_ACCOUNT);
        }).execute();
    }

    public String acquireAccessToken(boolean isRefresh, String authcode) throws IOException, JSONException {
        URL url = new URL(authTokenUrl);
        Logging.i("MicrosoftLogin", "isRefresh=" + isRefresh);

        String formData = convertToFormData(
                "client_id", "00000000402b5328",
                isRefresh ? "refresh_token" : "code", authcode,
                "grant_type", isRefresh ? "refresh_token" : "authorization_code",
                "redirect_url", "https://login.live.com/oauth20_desktop.srf",
                "scope", "service::user.auth.xboxlive.com::MBI_SSL"
        );

        //да пошла yf[eq1 она ваша джава 11
        HttpURLConnection conn = UrlManager.createHttpConnection(url);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("charset", "utf-8");
        conn.setRequestProperty("Content-Length", Integer.toString(formData.getBytes(StandardCharsets.UTF_8).length));
        conn.setRequestMethod("POST");
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.connect();
        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(formData.getBytes(StandardCharsets.UTF_8));
        }
        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
            msRefreshToken = jo.getString("refresh_token");
            conn.disconnect();
            return jo.getString("access_token");
            //acquireXBLToken(jo.getString("access_token"));
        }else{
            throw getResponseThrowable(conn);
        }
    }

    private String acquireXBLToken(String accessToken) throws IOException, JSONException {
        URL url = new URL(xblAuthUrl);

        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("AuthMethod", "RPS");
        properties.put("SiteName", "user.auth.xboxlive.com");
        properties.put("RpsTicket", accessToken);
        data.put("Properties",properties);
        data.put("RelyingParty", "http://auth.xboxlive.com");
        data.put("TokenType", "JWT");

        String req = data.toString();
        HttpURLConnection conn = UrlManager.createHttpConnection(url);
        setCommonProperties(conn, req);
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }
        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
            conn.disconnect();
            return jo.getString("Token");
            //acquireXsts(jo.getString("Token"));
        }else{
            throw getResponseThrowable(conn);
        }
    }

    /** @return [uhs, token]*/
    private @NonNull String[] acquireXsts(String xblToken) throws IOException, JSONException {
        URL url = new URL(xstsAuthUrl);

        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("SandboxId", "RETAIL");
        properties.put("UserTokens", new JSONArray(Collections.singleton(xblToken)));
        data.put("Properties", properties);
        data.put("RelyingParty", "rp://api.minecraftservices.com/");
        data.put("TokenType", "JWT");

        String req = data.toString();
        HttpURLConnection conn = UrlManager.createHttpConnection(url);
        setCommonProperties(conn, req);
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }

        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
            String uhs = jo.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
            String token = jo.getString("Token");
            conn.disconnect();
            return new String[]{uhs, token};
            //acquireMinecraftToken(uhs,jo.getString("Token"));
        }else if(conn.getResponseCode() == 401) {
            String responseContents = Tools.read(conn.getErrorStream());
            JSONObject jo = new JSONObject(responseContents);
            long xerr = jo.optLong("XErr", -1);
            Integer locale_id = XSTS_ERRORS.get(xerr);
            if(locale_id != null) {
                throw new PresentedException(new RuntimeException(responseContents), locale_id, false);
            }
            throw new PresentedException(new RuntimeException(responseContents), R.string.xerr_unknown, true, xerr);
        }else{
            throw getResponseThrowable(conn);
        }
    }

    private String acquireMinecraftToken(String xblUhs, String xblXsts) throws IOException, JSONException {
        URL url = new URL(mcLoginUrl);

        JSONObject data = new JSONObject();
        data.put("identityToken", "XBL3.0 x=" + xblUhs + ";" + xblXsts);

        String req = data.toString();
        HttpURLConnection conn = UrlManager.createHttpConnection(url);
        setCommonProperties(conn, req);
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }

        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
            conn.disconnect();
            mcToken = jo.getString("access_token");
            //checkMcProfile(jo.getString("access_token"));
            return jo.getString("access_token");
        }else{
            throw getResponseThrowable(conn);
        }
    }

    private void fetchOwnedItems(String mcAccessToken) throws IOException {
        URL url = new URL(mcStoreUrl);

        HttpURLConnection conn = UrlManager.createHttpConnection(url);
        conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
        conn.setUseCaches(false);
        conn.connect();
        if(conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
            throw getResponseThrowable(conn);
        }
        // We don't need any data from this request, it just needs to happen in order for
        // the MS servers to work properly. The data from this is practically useless
        // as it does not indicate whether the user owns the game through Game Pass.
    }

    private void checkMcProfile(String mcAccessToken) throws IOException, JSONException {
        URL url = new URL(mcProfileUrl);

        HttpURLConnection conn = UrlManager.createHttpConnection(url);
        conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
        conn.setUseCaches(false);
        conn.connect();

        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            String s= Tools.read(conn.getInputStream());
            conn.disconnect();
            JSONObject jsonObject = new JSONObject(s);
            String name = (String) jsonObject.get("name");
            String uuid = (String) jsonObject.get("id");
            String uuidDashes = uuid.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"
            );
            doesOwnGame = true;
            Logging.i("MicrosoftLogin","UserName = " + name);
            Logging.i("MicrosoftLogin","Uuid Minecraft = " + uuidDashes);
            mcName=name;
            mcUuid=uuidDashes;
        }else{
            Logging.i("MicrosoftLogin","It seems that this Microsoft Account does not own the game.");
            doesOwnGame = false;
            throw new PresentedException(new RuntimeException(conn.getResponseMessage()), R.string.minecraft_not_owned, true);
            //throwResponseError(conn);
        }
    }

    /** Wrapper to ease notifying the listener */
    private void notifyProgress(int step, String stepString) {
        ProgressLayout.setProgress(ProgressLayout.LOGIN_ACCOUNT, step * 20, R.string.account_login_microsoft_progress, stepString);
    }


    /** Set common properties for the connection. Given that all requests are POST, interactivity is always enabled */
    private static void setCommonProperties(HttpURLConnection conn, String formData) {
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("charset", "utf-8");
        try {
            conn.setRequestProperty("Content-Length", Integer.toString(formData.getBytes(StandardCharsets.UTF_8).length));
            conn.setRequestMethod("POST");
        }catch (ProtocolException e) {
            Logging.e("MicrosoftAuth", e.toString());
        }
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
    }

    /**
     * @param data A series a strings: key1, value1, key2, value2...
     * @return the data converted as a form string for a POST request
     */
    private static String convertToFormData(String... data) throws UnsupportedEncodingException {
        StringBuilder builder = new StringBuilder();
        for(int i=0; i<data.length; i+=2){
            if (builder.length() > 0) builder.append("&");
            builder.append(URLEncoder.encode(data[i], "UTF-8"))
                    .append("=")
                    .append(URLEncoder.encode(data[i+1], "UTF-8"));
        }
        return builder.toString();
    }

    private RuntimeException getResponseThrowable(HttpURLConnection conn) throws IOException {
        Logging.i("MicrosoftLogin", "Error code: " + conn.getResponseCode() + ": " + conn.getResponseMessage());
        if(conn.getResponseCode() == 429) {
            return new PresentedException(R.string.microsoft_login_retry_later, false);
        }
        return new RuntimeException(conn.getResponseMessage());
    }
}
