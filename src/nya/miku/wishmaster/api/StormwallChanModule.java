/*
 * Overchan Android (Meta Imageboard Client)
 * Copyright (C) 2014-2016  miku-nyan <https://github.com/miku-nyan>
 *     
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package nya.miku.wishmaster.api;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.CaptchaModel;
import nya.miku.wishmaster.common.IOUtils;
import nya.miku.wishmaster.http.interactive.InteractiveException;
import nya.miku.wishmaster.http.stormwall.StormwallExceptionRecaptcha;
import nya.miku.wishmaster.http.stormwall.StormwallExceptionAntiDDOS;
import nya.miku.wishmaster.http.streamer.HttpRequestException;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpResponseModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;
import nya.miku.wishmaster.http.streamer.HttpWrongResponseDetector;
import nya.miku.wishmaster.http.streamer.HttpWrongResponseException;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONObject;

public abstract class StormwallChanModule extends AbstractChanModule {
    
    protected static final String PREF_KEY_STORMWALL_COOKIE_VALUE1 = "PREF_KEY_STORMWALL_COOKIE1";
    protected static final String PREF_KEY_STORMWALL_COOKIE_VALUE2 = "PREF_KEY_STORMWALL_COOKIE2";
    protected static final String PREF_KEY_STORMWALL_COOKIE_DOMAIN = "PREF_KEY_STORMWALL_COOKIE_DOMAIN";

    protected static final String STORMWALL_COOKIE_NAME1 = "_JHASH__";
    protected static final String STORMWALL_COOKIE_NAME2 = "_HASH__";
    
    public StormwallChanModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }
    
    protected boolean canStormwall() {
        return true;
    }
    
    protected String getStormwallCookieDomain() {
        return preferences.getString(getSharedKey(PREF_KEY_STORMWALL_COOKIE_DOMAIN), null);
    }
    
    private static final HttpWrongResponseDetector stormwallDetector = new HttpWrongResponseDetector() {
        @Override
        public void check(final HttpResponseModel model) {
            boolean cacheControl = false;
            boolean contentType = false;
            boolean contentLength = false;
            for (Header header: model.headers) {
                if (header.getName().equalsIgnoreCase("cache-control") && header.getValue().equalsIgnoreCase("no-cache")) {
                    cacheControl = true;
                } else if (header.getName().equalsIgnoreCase("content-type") && header.getValue().equalsIgnoreCase("text/html; charset=utf-8")) {
                    contentType = true;
                } else if (header.getName().equalsIgnoreCase("content-length")) {
                    int size = 0;
                    try {
                        size = Integer.parseInt(header.getValue());
                    } catch (Exception e) {}
                    if (size >= 1100 && size <= 1150) {
                        contentLength = true;
                    }
                }
            }
            if (cacheControl && contentType && contentLength) {
                HttpWrongResponseException e = new HttpWrongResponseException("Stormwall");
                e.setHtmlBytes(HttpStreamer.tryGetBytes(model.stream));
                throw e;
            }
        }
    };

    private void handleWrongResponse(String url, HttpWrongResponseException e) throws HttpWrongResponseException, InteractiveException {
        if ("Stormwall".equals(e.getMessage())) {
            String fixedUrl = fixRelativeUrl(url);
            String html = e.getHtmlString();
            InteractiveException swe = StormwallExceptionRecaptcha.newInstance(fixedUrl, html, getChanName());
            if (swe == null) swe = StormwallExceptionAntiDDOS.newInstance(fixedUrl, html, getChanName());
            if (swe != null) throw swe;
        }
        throw e;
    }

    protected void checkForStormwall(String url, HttpResponseModel model) throws HttpWrongResponseException, InteractiveException {
        try {
            stormwallDetector.check(model);
        } catch (HttpWrongResponseException e) {
            handleWrongResponse(url, e);
        }
    }

    @Override
    protected void initHttpClient() {
        if (canStormwall()) {
            String stormwallCookieValue1 = preferences.getString(getSharedKey(PREF_KEY_STORMWALL_COOKIE_VALUE1), null);
            String stormwallCookieValue2 = preferences.getString(getSharedKey(PREF_KEY_STORMWALL_COOKIE_VALUE2), null);
            String stormwallCookieDomain = getStormwallCookieDomain();
            if (stormwallCookieValue1 != null && stormwallCookieValue2 != null && stormwallCookieDomain != null) {
                BasicClientCookie c;
                c = new BasicClientCookie(STORMWALL_COOKIE_NAME1, stormwallCookieValue1);
                c.setDomain(stormwallCookieDomain);
                httpClient.getCookieStore().addCookie(c);
                c = new BasicClientCookie(STORMWALL_COOKIE_NAME2, stormwallCookieValue2);
                c.setDomain(stormwallCookieDomain);
                httpClient.getCookieStore().addCookie(c);
            }
        }
    }
    
    @Override
    public void saveCookie(Cookie cookie) {
        super.saveCookie(cookie);
        if (cookie != null) {
            if (canStormwall()) {
               if (cookie.getName().equals(STORMWALL_COOKIE_NAME1)) {
                   preferences.edit().
                       putString(getSharedKey(PREF_KEY_STORMWALL_COOKIE_VALUE1), cookie.getValue()).
                       putString(getSharedKey(PREF_KEY_STORMWALL_COOKIE_DOMAIN), cookie.getDomain()).commit();
               } else if (cookie.getName().equals(STORMWALL_COOKIE_NAME2)) {
                   preferences.edit().
                       putString(getSharedKey(PREF_KEY_STORMWALL_COOKIE_VALUE2), cookie.getValue()).
                       putString(getSharedKey(PREF_KEY_STORMWALL_COOKIE_DOMAIN), cookie.getDomain()).commit();
               }
            }
        }
    }

    @Override
    public void clearCookies() {
        super.clearCookies();
        preferences.edit().
            remove(getSharedKey(PREF_KEY_STORMWALL_COOKIE_VALUE1)).
            remove(getSharedKey(PREF_KEY_STORMWALL_COOKIE_VALUE2)).
            remove(getSharedKey(PREF_KEY_STORMWALL_COOKIE_DOMAIN)).
            commit();
    }

    @Override
    protected CaptchaModel downloadCaptcha(String url, ProgressListener listener, CancellableTask task) throws Exception {
        Bitmap captchaBitmap = null;
        HttpRequestModel requestModel = HttpRequestModel.DEFAULT_GET;
        HttpResponseModel responseModel = HttpStreamer.getInstance().getFromUrl(url, requestModel, httpClient, listener, task);
        try {
            checkForStormwall(url, responseModel);
            InputStream imageStream = responseModel.stream;
            captchaBitmap = BitmapFactory.decodeStream(imageStream);
        } finally {
            responseModel.release();
        }
        CaptchaModel captchaModel = new CaptchaModel();
        captchaModel.type = CaptchaModel.TYPE_NORMAL;
        captchaModel.bitmap = captchaBitmap;
        return captchaModel;
    }

    @Override
    public void downloadFile(String url, OutputStream out, ProgressListener listener, CancellableTask task) throws Exception {
        if (!canStormwall()) {
            super.downloadFile(url, out, listener, task);
            return;
        }
        String fixedUrl = fixRelativeUrl(url);
        try {
            HttpRequestModel rqModel = HttpRequestModel.DEFAULT_GET;
            HttpStreamer.getInstance().downloadFileFromUrl(fixedUrl, out, rqModel, httpClient, listener, task, false, stormwallDetector);
        } catch (HttpWrongResponseException e) {
            handleWrongResponse(fixedUrl, e);
        }
    }

    @Override
    protected JSONObject downloadJSONObject(String url, boolean checkIfModified, ProgressListener listener, CancellableTask task) throws Exception {
        if (!canStormwall()) return super.downloadJSONObject(url, checkIfModified, listener, task);
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModified).build();
        try {
            JSONObject object = HttpStreamer.getInstance().getJSONObjectFromUrl(url, rqModel, httpClient, listener, task, false, stormwallDetector);
            if (task != null && task.isCancelled()) throw new Exception("interrupted");
            if (listener != null) listener.setIndeterminate();
            return object;
        } catch (HttpWrongResponseException e) {
            handleWrongResponse(url, e);
            return null;
        }
    }

    @Override
    protected JSONArray downloadJSONArray(String url, boolean checkIfModified, ProgressListener listener, CancellableTask task) throws Exception {
        if (!canStormwall()) return super.downloadJSONArray(url, checkIfModified, listener, task);
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModified).build();
        try {
            JSONArray array = HttpStreamer.getInstance().getJSONArrayFromUrl(url, rqModel, httpClient, listener, task, false, stormwallDetector);
            if (task != null && task.isCancelled()) throw new Exception("interrupted");
            if (listener != null) listener.setIndeterminate();
            return array;
        } catch (HttpWrongResponseException e) {
            handleWrongResponse(url, e);
            return null;
        }
    }
}
