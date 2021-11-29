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

package nya.miku.wishmaster.chans.makaba;

import static nya.miku.wishmaster.chans.makaba.MakabaConstants.*;
import static nya.miku.wishmaster.chans.makaba.MakabaJsonMapper.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpHeaders;
import cz.msebera.android.httpclient.message.BasicHeader;
import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.CloudflareChanModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.CaptchaModel;
import nya.miku.wishmaster.api.models.DeletePostModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.models.ThreadModel;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.api.util.ChanModels;
import nya.miku.wishmaster.api.util.LazyPreferences;
import nya.miku.wishmaster.api.util.UrlPathUtils;
import nya.miku.wishmaster.common.IOUtils;
import nya.miku.wishmaster.common.Logger;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.hashwall.HashwallExceptionAntiDDOS;
import nya.miku.wishmaster.http.interactive.InteractiveException;
import nya.miku.wishmaster.http.recaptcha.Recaptcha2;
import nya.miku.wishmaster.http.recaptcha.Recaptcha2solved;
import nya.miku.wishmaster.http.streamer.HttpRequestException;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpResponseModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;
import nya.miku.wishmaster.http.streamer.HttpWrongResponseDetector;
import nya.miku.wishmaster.http.streamer.HttpWrongResponseException;
import nya.miku.wishmaster.http.streamer.HttpWrongStatusCodeException;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONException;
import nya.miku.wishmaster.lib.org_json.JSONObject;

import cz.msebera.android.httpclient.HttpEntity;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.support.v4.content.res.ResourcesCompat;
import android.text.InputType;

/**
 * Класс, осуществляющий взаимодействия с АИБ 2ch.hk (движок makaba)
 * @author miku-nyan
 *
 */

public class MakabaModule extends CloudflareChanModule {
    private static final String TAG = "MakabaModule"; 
    
    /** что-то типа '2ch.hk' */
    private String domain;
    /** что-то типа 'https://2ch.hk/' */
    private String domainUrl;
    
    /** id текущей mail.ru капчи*/
    private String captchaMailRuId;

    /** id текущей капчи*/
    private String captchaId;
    /** тип текущей капчи*/
    private int captchaType;

    
    /** карта досок из списка boards.json (содержит только базовые параметры) */
    private Map<String, BoardModel> boardsMap = null;
    /** полноценная карта досок (содержит параметры посещенных досок) */
    private Map<String, BoardModel> customBoardsMap = new HashMap<>();
    
    public MakabaModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
        updateDomain(
                preferences.getString(getSharedKey(PREF_KEY_DOMAIN), DEFAULT_DOMAIN),
                preferences.getBoolean(getSharedKey(PREF_KEY_USE_HTTPS_MAKABA), true));
    }

    public boolean canHashwall() {
        return true;
    }
    
    @Override
    public String getChanName() {
        return CHAN_NAME;
    }
    
    @Override
    public String getDisplayingName() {
        return "Два.ч (2ch.hk)";
    }
    
    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_makaba, null);
    }

    private static final HttpWrongResponseDetector hashwallDetector = new HttpWrongResponseDetector() {
        @Override
        public void check(final HttpResponseModel model) {
            if (model.statusCode == 303 && model.locationHeader != null) {
                throw new HttpWrongResponseException("Hashwall");
            }
            for (Header header: model.headers) {
                if (header.getName().equalsIgnoreCase("Set-Cookie")) {
                    Matcher m = PATTERN_HASHWALL_COOKIE.matcher(header.getValue());
                    if (m.find()) throw new HttpWrongResponseException("Hashwall");
                }
            }
        }
    };

    private void handleWrongResponse(String url, HttpWrongResponseException e) throws HttpWrongResponseException, InteractiveException {
        if ("Hashwall".equals(e.getMessage())) {
            String fixedUrl = fixRelativeUrl(url);
            InteractiveException hwe = HashwallExceptionAntiDDOS.newInstance(fixedUrl, getChanName());
            if (hwe != null) throw hwe;
            else throw new HttpWrongResponseException(resources.getString(R.string.error_antiddos));
        }
        throw e;
    }

    private void checkForHashwall(String url, HttpResponseModel model) throws HttpWrongResponseException, InteractiveException {
        try {
            hashwallDetector.check(model);
        } catch (HttpWrongResponseException e) {
            handleWrongResponse(url, e);
        }
    }
    
    @Override
    protected void initHttpClient() {
        super.initHttpClient();
        setCookie(
                preferences.getString(getSharedKey(PREF_KEY_USERCODE_COOKIE_DOMAIN), null),
                USERCODE_COOKIE_NAME,
                preferences.getString(getSharedKey(PREF_KEY_USERCODE_COOKIE_VALUE), null));
        setPasscodeCookie();
        if (canHashwall()) {
            String name = preferences.getString(getSharedKey(PREF_KEY_HASHWALL_COOKIE_NAME), null);
            String value = preferences.getString(getSharedKey(PREF_KEY_HASHWALL_COOKIE_VALUE), null);
            String domain = preferences.getString(getSharedKey(PREF_KEY_HASHMWALL_COOKIE_DOMAIN), null);
            if (name != null && value != null && domain != null) {
                BasicClientCookie c = new BasicClientCookie(name, value);
                c.setDomain(domain);
                httpClient.getCookieStore().addCookie(c);
            }
        }
    }
    
    private void updateDomain(String domain, boolean useHttps) {
        if (domain.endsWith("/")) domain = domain.substring(0, domain.length() - 1);
        if (domain.contains("//")) domain = domain.substring(domain.indexOf("//") + 2);
        if (domain.equals("")) domain = DEFAULT_DOMAIN;
        this.domain = domain;
        this.domainUrl = (useHttps ? "https://" : "http://") + domain + "/";
    }
    
    /** Установить cookie к текущему клиенту */
    private void setCookie(String domain, String name, String value) {
        if (value == null || value.equals("")) return;
        BasicClientCookie c = new BasicClientCookie(name, value);
        c.setDomain(domain == null || domain.equals("") ? ("." + this.domain) : domain);
        c.setPath("/");
        httpClient.getCookieStore().addCookie(c);
    }
    
    @Override
    public void saveCookie(Cookie cookie) {
        super.saveCookie(cookie);
        if (cookie != null) {
            if (cookie.getName().equals(USERCODE_COOKIE_NAME)) {
                preferences.edit().
                        putString(getSharedKey(PREF_KEY_USERCODE_COOKIE_DOMAIN), cookie.getDomain()).
                        putString(getSharedKey(PREF_KEY_USERCODE_COOKIE_VALUE), cookie.getValue()).commit();
            } else if (canHashwall() && "Hashwall".equals(cookie.getComment())) {
                preferences.edit().
                        putString(getSharedKey(PREF_KEY_HASHWALL_COOKIE_NAME), cookie.getName()).
                        putString(getSharedKey(PREF_KEY_HASHWALL_COOKIE_VALUE), cookie.getValue()).
                        putString(getSharedKey(PREF_KEY_HASHMWALL_COOKIE_DOMAIN), cookie.getDomain()).commit();
            }
        }
    }
    
    @Override
    public void clearCookies() {
        super.clearCookies();
        preferences.edit().
            remove(getSharedKey(PREF_KEY_USERCODE_COOKIE_DOMAIN)).
            remove(getSharedKey(PREF_KEY_USERCODE_COOKIE_VALUE)).
            remove(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_DOMAIN)).
            remove(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_VALUE)).
            remove(getSharedKey(PREF_KEY_HASHWALL_COOKIE_NAME)).
            remove(getSharedKey(PREF_KEY_HASHWALL_COOKIE_VALUE)).
            remove(getSharedKey(PREF_KEY_HASHMWALL_COOKIE_DOMAIN)).
            commit();
    }

    private void saveUsercodeCookie() {
        for (Cookie cookie : httpClient.getCookieStore().getCookies()) {
            if (cookie.getName().equals(USERCODE_COOKIE_NAME) && cookie.getDomain().contains(domain)) saveCookie(cookie);
        }
    }
    
    private void setPasscodeCookie() {
        setCookie(
                preferences.getString(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_DOMAIN), null),
                USERCODE_NOCAPTCHA_COOKIE_NAME,
                preferences.getString(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_VALUE), null));
    }

    private void addMobileAPIPreference(PreferenceGroup group) {
        final Context context = group.getContext();
        CheckBoxPreference mobileAPIPref = new LazyPreferences.CheckBoxPreference(context);
        mobileAPIPref.setTitle(R.string.makaba_prefs_mobile_api);
        mobileAPIPref.setSummary(R.string.pref_only_new_posts_summary);
        mobileAPIPref.setKey(getSharedKey(PREF_KEY_MOBILE_API));
        mobileAPIPref.setDefaultValue(true);
        group.addPreference(mobileAPIPref);
    }
    
    private void addCaptchaPreferences(PreferenceGroup group) {
        final Context context = group.getContext();
        final ListPreference captchaPreference = new LazyPreferences.ListPreference(context); //captcha_type
        captchaPreference.setTitle(R.string.pref_captcha_type);
        captchaPreference.setDialogTitle(R.string.pref_captcha_type);
        captchaPreference.setKey(getSharedKey(PREF_KEY_CAPTCHA_TYPE));
        captchaPreference.setEntryValues(CAPTCHA_TYPES_KEYS);
        captchaPreference.setEntries(CAPTCHA_TYPES);
        captchaPreference.setDefaultValue(CAPTCHA_TYPE_DEFAULT);
        int i = Arrays.asList(CAPTCHA_TYPES_KEYS).indexOf(preferences.getString(getSharedKey(PREF_KEY_CAPTCHA_TYPE), CAPTCHA_TYPE_DEFAULT));
        if (i >= 0) captchaPreference.setSummary(CAPTCHA_TYPES[i]);
        group.addPreference(captchaPreference);
    }

    private void addPasscodePreferences(PreferenceGroup group) {
        final Context context = group.getContext();
        EditTextPreference passcodePref = new EditTextPreference(context);
        passcodePref.setTitle(R.string.pref_makaba_passcode);
        passcodePref.setKey(getSharedKey(PREF_KEY_PASSCODE));
        group.addPreference(passcodePref);
    }

    public boolean getCaptchaAutoUpdatePreference(){
        return preferences.getBoolean(getSharedKey(PREF_KEY_CAPTCHA_AUTO_UPDATE), false);
    }
    
    /** Добавить категорию настроек домена (в т.ч. https) */
    private void addDomainPreferences(PreferenceGroup group) {
        Context context = group.getContext();
        Preference.OnPreferenceChangeListener updateDomainListener = new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (preference.getKey().equals(getSharedKey(PREF_KEY_DOMAIN))) {
                    updateDomain((String) newValue, preferences.getBoolean(getSharedKey(PREF_KEY_USE_HTTPS_MAKABA), true));
                    return true;
                } else if (preference.getKey().equals(getSharedKey(PREF_KEY_USE_HTTPS_MAKABA))) {
                    updateDomain(preferences.getString(getSharedKey(PREF_KEY_DOMAIN), DEFAULT_DOMAIN), (boolean)newValue);
                    return true;
                }
                return false;
            }
        };
        PreferenceCategory domainCat = new PreferenceCategory(context);
        domainCat.setTitle(R.string.makaba_prefs_domain_category);
        group.addPreference(domainCat);
        EditTextPreference domainPref = new EditTextPreference(context); //поле ввода домена
        domainPref.setTitle(R.string.pref_domain);
        domainPref.setDialogTitle(R.string.pref_domain);
        domainPref.setSummary(resources.getString(R.string.pref_domain_summary, DOMAINS_HINT));
        domainPref.setKey(getSharedKey(PREF_KEY_DOMAIN));
        domainPref.getEditText().setHint(DEFAULT_DOMAIN);
        domainPref.getEditText().setSingleLine();
        domainPref.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        domainPref.setOnPreferenceChangeListener(updateDomainListener);
        domainCat.addPreference(domainPref);
        CheckBoxPreference httpsPref = new LazyPreferences.CheckBoxPreference(context); //чекбокс "использовать https"
        httpsPref.setTitle(R.string.pref_use_https);
        httpsPref.setSummary(R.string.pref_use_https_summary);
        httpsPref.setKey(getSharedKey(PREF_KEY_USE_HTTPS_MAKABA));
        httpsPref.setDefaultValue(true);
        httpsPref.setOnPreferenceChangeListener(updateDomainListener);
        domainCat.addPreference(httpsPref);
    }
    
    @Override
    public void addPreferencesOnScreen(final PreferenceGroup preferenceScreen) {
        addMobileAPIPreference(preferenceScreen);
        addCaptchaPreferences(preferenceScreen);
        addPasscodePreferences(preferenceScreen);
        addCaptchaAutoUpdatePreference(preferenceScreen);
        addDomainPreferences(preferenceScreen);
        addProxyPreferences(preferenceScreen);
        addClearCookiesPreference(preferenceScreen);
    }

   private boolean checkPasscode(String boardName, ProgressListener listener, CancellableTask task) {
       if (preferences.getString(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_VALUE), "").equals("")) return false;
       setPasscodeCookie();
       String captchaUrl = domainUrl + "api/captcha/" +  getUsingCaptchaKey() + "/id?board=" + boardName;
       JSONObject jsonResponse = null;
       try {
           jsonResponse = downloadJSONObject(captchaUrl, false, listener, task);
       } catch (Exception e) {
           return false;
       }
       return jsonResponse.optInt("result", -1) == 2;
   }

    private boolean obtainNocaptchaUsercode(String boardName, ProgressListener listener, CancellableTask task) throws Exception {
        String passcode = preferences.getString(getSharedKey(PREF_KEY_PASSCODE), "");
        if (!passcode.equals("") && checkPasscode(boardName, listener, task)) return true;
        String url = domainUrl + "makaba/makaba.fcgi";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task);
        if (passcode.equals("")) {
            if (preferences.getString(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_VALUE), "").equals("")) {
                setPasscodeCookie();
                return false;
            }
            postEntityBuilder.addString("task", "logout");
        } else {
            postEntityBuilder.addString("task", "auth");
            postEntityBuilder.addString("usercode", passcode);
        }
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build()).setNoRedirect(true).build();
        HttpResponseModel response = null;
        try {
            response = HttpStreamer.getInstance().getFromUrl(url, request, httpClient, null, task);
            if (response.statusCode != 301 && response.statusCode != 302) {
                if (canHashwall()) checkForHashwall(url, response);
                if (response.stream == null)
                    throw new HttpRequestException(new NullPointerException());
                ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                IOUtils.copyStream(response.stream, output);
                String html = new String(output.toByteArray());
                if (html.contains("Ваш код не существует!"))
                    throw new Exception("Invalid passcode!");
                throw new HttpWrongStatusCodeException(response.statusCode, response.statusCode + " - " + response.statusReason, html.getBytes());
            }
        } catch (HttpWrongStatusCodeException e) {
            if (canCloudflare()) checkCloudflareError(e, url);
            throw e;
        } finally {
            if (response != null) response.release();
        }
        String passcodeCookie = "";
        String passcodeDomain = "";
        for (Cookie cookie : httpClient.getCookieStore().getCookies()) {
            if (cookie.getName().equals(USERCODE_NOCAPTCHA_COOKIE_NAME) && cookie.getDomain().contains(domain)) {
                passcodeCookie = cookie.getValue();
                passcodeDomain = cookie.getDomain();
                break;
            }
        }
        preferences.edit()
                .putString(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_VALUE), passcodeCookie)
                .putString(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_DOMAIN), passcodeDomain)
                .commit();
        return checkPasscode(boardName, listener, task);
    }

    private int getUsingCaptchaType() {
        String key = preferences.getString(getSharedKey(PREF_KEY_CAPTCHA_TYPE), CAPTCHA_TYPE_DEFAULT);
        if (!Arrays.asList(CAPTCHA_TYPES_KEYS).contains(key)) key = CAPTCHA_TYPE_DEFAULT;
        switch (key) {
            case "2chcaptcha":
                return CAPTCHA_2CHAPTCHA;
            case "recaptcha":
                return CAPTCHA_RECAPTCHA;
            case "recaptcha-fallback":
                return CAPTCHA_RECAPTCHA_FALLBACK;
            case "mailru":
                return CAPTCHA_MAILRU;
        }
        throw new IllegalStateException("wrong captcha settings");
    }

    private String getUsingCaptchaKey() {
        switch(getUsingCaptchaType()) {
            case CAPTCHA_2CHAPTCHA:
                return "2chcaptcha";
            case CAPTCHA_RECAPTCHA:
            case CAPTCHA_RECAPTCHA_FALLBACK:
                return "recaptcha";
            case CAPTCHA_MAILRU:
                return "mailru";
            default:
                return CAPTCHA_TYPE_DEFAULT;
        }
    }
    
    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        List<SimpleBoardModel> list = new ArrayList<>();
        Map<String, BoardModel> newMap = new HashMap<>();

        String url = domainUrl + "boards.json";

        JSONObject response = downloadJSONObject(url, (oldBoardsList != null && this.boardsMap != null), listener, task);
        if (response == null) return oldBoardsList;

        JSONArray boardsList = response.getJSONArray("boards");

        for (int i = 0, len=boardsList.length(); i < len; i++) {
            JSONObject currentBoard = boardsList.getJSONObject(i);
            BoardModel model = mapBoardModel(currentBoard, true, resources);
            newMap.put(model.boardName, model);
            list.add(new SimpleBoardModel(model));
        }
        this.boardsMap = newMap;

        Collections.sort(list, new Comparator<SimpleBoardModel>() {
            @Override
            public int compare(SimpleBoardModel o1, SimpleBoardModel o2) {
                return o1.boardName.compareTo(o2.boardName);
            }
        });

        SimpleBoardModel[] result = new SimpleBoardModel[list.size()];
        boolean[] copied = new boolean[list.size()];
        int curIndex = 0;
        for (String category : CATEGORIES) {
            for (int i=0; i<list.size(); ++i) {
                if (list.get(i).boardCategory.equals(category)) {
                    result[curIndex++] = list.get(i);
                    copied[i] = true;
                }
            }
        }
        for (int i=0; i<list.size(); ++i) {
            if (!copied[i]) {
                result[curIndex++] = list.get(i);
            }
        }

        return result;
    }

    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        try {
            if (this.customBoardsMap.containsKey(shortName)) {
                return this.customBoardsMap.get(shortName);
            }

            String url = domainUrl + shortName + "/index.json";
            JSONObject json;
            try {
                json = downloadJSONObject(url, false, listener, task);
            } finally {
                HttpStreamer.getInstance().removeFromModifiedMap(url);
            }
            BoardModel result = mapBoardModel(json, false, resources);
            if (!shortName.equals(result.boardName)) throw new Exception();
            this.customBoardsMap.put(result.boardName, result);
            return result;
        } catch (Exception e) {
            Logger.e(TAG, e);
            if (this.boardsMap == null) {
                try {
                    getBoardsList(listener, task, null);
                } catch (Exception e2) {
                    Logger.d(TAG, "cannot update boards list");
                }
            }
            if (this.boardsMap != null) {
                if (this.boardsMap.containsKey(shortName)) {
                    return this.boardsMap.get(shortName);
                }
            }
            return defaultBoardModel(shortName, resources);
        }
    }

    @Override
    public ThreadModel[] getThreadsList(String boardName, int page, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
        String url = domainUrl + boardName + "/" + (page == 0 ? "index" : Integer.toString(page)) + ".json";
        JSONObject index = downloadJSONObject(url, (oldList != null), listener, task);
        if (index == null) return oldList;

        try { // кэширование модели BoardModel во время загрузки списка тредов
            BoardModel boardModel = mapBoardModel(index, false, resources);
            if (boardName.equals(boardModel.boardName)) {
                this.customBoardsMap.put(boardModel.boardName, boardModel);
            }
        } catch (Exception e) { /* если не получилось сейчас замапить модель доски, и фиг с ней */ }

        JSONArray threads = index.getJSONArray("threads");
        ThreadModel[] result = new ThreadModel[threads.length()];
        for (int i=0; i<threads.length(); ++i) {
            result[i] = mapThreadModel(threads.getJSONObject(i), boardName);
        }
        return result;
    }

    @Override
    public ThreadModel[] getCatalog(String boardName, int catalogType, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
        Exception last = null;
        for (String url : new String[] {
                domainUrl + boardName + "/" + CATALOG_TYPES[catalogType] + ".json",
                domainUrl + boardName + "/catalog.json"
        }) {
            try {
                JSONObject json = downloadJSONObject(url, (oldList != null), listener, task);
                if (json == null) return oldList;
                JSONArray threads = json.getJSONArray("threads");
                ThreadModel[] result = new ThreadModel[threads.length()];
                for (int i=0; i<threads.length(); ++i) {
                    JSONObject curThread = threads.getJSONObject(i);
                    ThreadModel model = new ThreadModel();
                    model.threadNumber = curThread.getString("num");
                    model.postsCount = curThread.optInt("posts_count", -2) + 1;
                    try {
                        model.attachmentsCount = curThread.getInt("files_count");
                        model.attachmentsCount += curThread.getJSONArray("files").length();
                    } catch (Exception e) {
                        Logger.e(TAG, e);
                    }
                    model.isSticky = curThread.optInt("sticky", 0) != 0;
                    model.isClosed = curThread.optInt("closed", 0) != 0;
                    model.isCyclical = curThread.optInt("endless", 0) != 0;
                    model.posts = new PostModel[] { mapPostModel(curThread, boardName) };
                    result[i] = model;
                }
                return result;
            } catch (HttpWrongStatusCodeException cf) {
                checkCloudflareError(cf, url);
                last = cf;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    @Override
    public PostModel[] getPostsList(String boardName, String threadNumber, ProgressListener listener, CancellableTask task, PostModel[] oldList)
            throws Exception {
        boolean mobileAPI = preferences.getBoolean(getSharedKey(PREF_KEY_MOBILE_API), true);
        if (!mobileAPI || oldList == null || oldList.length == 0) {
            String url = domainUrl + boardName + "/res/" + threadNumber + ".json";
            JSONObject object = downloadJSONObject(url, (oldList != null), listener, task);
            if (object == null) return oldList;
            JSONArray postsArray = object.getJSONArray("threads").getJSONObject(0).getJSONArray("posts");
            PostModel[] posts = new PostModel[postsArray.length()];
            for (int i=0; i<postsArray.length(); ++i) {
                posts[i] = mapPostModel(postsArray.getJSONObject(i), boardName);
            }
            if (oldList != null) {
                posts = ChanModels.mergePostsLists(Arrays.asList(oldList), Arrays.asList(posts));
            }
            return posts;
        }
        try {
            String lastPost = oldList[oldList.length-1].number;
            String url = domainUrl + "api/mobile/v2/after/" + boardName + "/" + threadNumber + "/" + lastPost;
            JSONObject object = downloadJSONObject(url, false, listener, task);
            if (object.getInt("result") == 0) {
                JSONObject makabaError = object.getJSONObject("error");
                String reason = makabaError.getString("message");
                throw new Exception(reason);
            }
            JSONArray newPostsArray = object.getJSONArray("posts");
            PostModel[] newPosts = new PostModel[newPostsArray.length()];
            for (int i=0; i<newPostsArray.length(); ++i) {
                newPosts[i] = mapPostModel(newPostsArray.getJSONObject(i), boardName);
            }
            long lastNum = Long.parseLong(lastPost);
            ArrayList<PostModel> list = new ArrayList<>(Arrays.asList(oldList));
            for (PostModel newPost : newPosts) {
                if (Long.parseLong(newPost.number) > lastNum) {
                    list.add(newPost);
                }
            }
            return list.toArray(new PostModel[0]);
        } catch (JSONException e) {
            throw new Exception("Mobile API error");
        }
    }

    @Override
    public PostModel[] search(String boardName, String searchRequest, ProgressListener listener, CancellableTask task) throws Exception {
        if (searchRequest.startsWith(HASHTAG_PREFIX)) {
            String hashtag = " /" + searchRequest.substring(HASHTAG_PREFIX.length()) + "/";
            ThreadModel[] threads = getCatalog(boardName, 0, listener, task, null);
            List<PostModel> posts = new ArrayList<>();
            for (ThreadModel thread : threads) {
                if (thread.posts[0].subject.contains(hashtag))
                    posts.add(thread.posts[0]);
            }
            return posts.toArray(new PostModel[0]);
        } else {
            String url = domainUrl + "makaba/makaba.fcgi";
            HttpEntity postEntity = ExtendedMultipartBuilder.create().
                    addString("task", "search").
                    addString("board", boardName).
                    addString("find", searchRequest).
                    addString("json", "1").
                    build();
            HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntity).setNoRedirect(true).build();
            JSONObject response = null;
            try {
                response = HttpStreamer.getInstance().getJSONObjectFromUrl(url, request, httpClient, listener, task, true,
                        (canHashwall() ? hashwallDetector : null));
            } catch (HttpWrongStatusCodeException e) {
                checkCloudflareError(e, url);
                throw e;
            } catch (HttpWrongResponseException he) {
                handleWrongResponse(url, he);
            }
            if (listener != null) listener.setIndeterminate();
            JSONArray posts = response.getJSONArray("posts");
            PostModel[] result = new PostModel[posts.length()];
            for (int i=0; i<posts.length(); ++i) {
                result[i] = mapPostModel(posts.getJSONObject(i), boardName);
            }
            return result;
        }
    }
    
    @Override
    public CaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        if (obtainNocaptchaUsercode(boardName, listener, task)) {
            return null;
        }
        int captchaType = getUsingCaptchaType();
        String captchaKey = getUsingCaptchaKey();
        
        String url = domainUrl + "api/captcha/" + captchaKey + "/id?board=" + boardName + (threadNumber != null ? "&thread=" + threadNumber : "");
        JSONObject response = downloadJSONObject(url, false, listener, task);
        switch (response.optInt("result", -1)) {
            case 1: //Enabled
                CaptchaModel captchaModel;
                captchaMailRuId = null;
                this.captchaType = captchaType;
                captchaId = response.optString("id");
                switch (captchaType) {
                    case CAPTCHA_2CHAPTCHA:
                        url = domainUrl + "api/captcha/" + captchaKey + "/show?id=" + captchaId;
                        captchaModel = downloadCaptcha(url, listener, task);
                        captchaModel.type = CaptchaModel.TYPE_NORMAL_DIGITS;
                        return captchaModel;
                    case CAPTCHA_RECAPTCHA:
                    case CAPTCHA_RECAPTCHA_FALLBACK:
                        return null;
                    case CAPTCHA_MAILRU:
                        UrlPageModel refererPage = new UrlPageModel();
                        refererPage.chanName = CHAN_NAME;
                        refererPage.boardName = boardName;
                        if (threadNumber == null) {
                            refererPage.type = UrlPageModel.TYPE_BOARDPAGE;
                            refererPage.boardPage = 0;
                        } else {
                            refererPage.type = UrlPageModel.TYPE_THREADPAGE;
                            refererPage.threadNumber = threadNumber;
                        }
                        String refererUrl = buildUrl(refererPage);
                        Header[] customHeaders = new Header[] { new BasicHeader(HttpHeaders.REFERER, refererUrl) };
                        captchaKey = response.optString("id");
                        String jsUrl = MAILRU_JS_URL + captchaKey;
                        Bitmap captchaBitmap = null;
                        HttpRequestModel requestModel = HttpRequestModel.builder().setGET().setCustomHeaders(customHeaders).build();
                        String jsResponse = HttpStreamer.getInstance().getStringFromUrl(jsUrl, requestModel, httpClient, listener, task, true);
                        Matcher mailRuIdMatcher = MAILRU_ID_PATTERN.matcher(jsResponse);
                        if (!mailRuIdMatcher.find()) throw new Exception("Couldn't get Mail.Ru captcha ID");
                        captchaMailRuId = mailRuIdMatcher.group(1);

                        Matcher mailRuUrlMatcher = MAILRU_URL_PATTERN.matcher(jsResponse);
                        String captchaUrl = mailRuUrlMatcher.find() ? mailRuUrlMatcher.group(1) : MAILRU_DEFAULT_CAPTCHA_URL;
                        HttpResponseModel responseModel = HttpStreamer.getInstance().getFromUrl(captchaUrl, requestModel, httpClient, listener, task);
                        try {
                            InputStream imageStream = responseModel.stream;
                            captchaBitmap = BitmapFactory.decodeStream(imageStream);
                        } finally {
                            responseModel.release();
                        }
                        captchaModel = new CaptchaModel();
                        captchaModel.type = CaptchaModel.TYPE_NORMAL;
                        captchaModel.bitmap = captchaBitmap;
                        return captchaModel;
                    default:
                        throw new IllegalStateException();
                }
            case 2: //VIP
            case 3: //Disabled
                this.captchaType = CAPTCHA_DISABLED;
                return null;
            case 0: //Fail
                throw new Exception(response.optString("description"));
            default:
                throw new Exception("Invalid captcha response");
        }
    }
    
    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String usercode_nocaptcha = preferences.getString(getSharedKey(PREF_KEY_NOCAPTCHA_COOKIE_VALUE), "");

        String url = domainUrl + "makaba/posting.fcgi?json=1";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString("task", "post").
                addString("board", model.boardName).
                addString("thread", model.threadNumber == null ? "0" : model.threadNumber);
        
        postEntityBuilder.addString("comment", model.comment);
        if (model.captchaAnswer.equals("") && !usercode_nocaptcha.equals("")) {
            postEntityBuilder.addString("usercode", usercode_nocaptcha);
        }
        String recaptcha2 = null;
        if (usercode_nocaptcha.equals("")) {
            switch (captchaType) {
                case CAPTCHA_2CHAPTCHA:
                    postEntityBuilder.
                        addString("captcha_type", "2chcaptcha").
                        addString("2chcaptcha_id", captchaId).
                        addString("2chcaptcha_value", model.captchaAnswer);
                    break;
                case CAPTCHA_RECAPTCHA:
                case CAPTCHA_RECAPTCHA_FALLBACK:
                    recaptcha2 = Recaptcha2solved.pop(captchaId);
                    if (recaptcha2 == null) {
                        boolean fallback = getUsingCaptchaType() == CAPTCHA_RECAPTCHA_FALLBACK;
                        throw Recaptcha2.obtain(domainUrl, captchaId, null, CHAN_NAME, fallback);
                    }
                    postEntityBuilder.
                        addString("captcha_type", "recaptcha").
                        addString("2chcaptcha_id", captchaId).
                        addString("g-recaptcha-response", recaptcha2);
                    break;
                case CAPTCHA_MAILRU:
                    if (captchaMailRuId != null) {
                        postEntityBuilder.addString("captcha_id", captchaMailRuId);
                        postEntityBuilder.addString("captcha_value", model.captchaAnswer);
                        postEntityBuilder.addString("captcha_type", "mailru");
                        postEntityBuilder.addString("2chcaptcha_id", captchaId);
                    }
                    break;
            }
        }
        if (task != null && task.isCancelled()) throw new InterruptedException("interrupted");
        
        if (model.subject != null) postEntityBuilder.addString("subject", model.subject);
        if (model.name != null) postEntityBuilder.addString("name", model.name);
        if (model.sage) postEntityBuilder.addString("email", "sage");
        else if (model.email != null) postEntityBuilder.addString("email", model.email);
        
        if (model.attachments != null) {
            String[] images = new String[] { "image1", "image2", "image3", "image4" };
            for (int i=0; i<model.attachments.length; ++i) {
                postEntityBuilder.addFile(images[i], model.attachments[i], model.randomHash);
            }
        }
        
        if (model.icon != -1) postEntityBuilder.addString("icon", Integer.toString(model.icon));
        
        //if (model.watermark) postEntityBuilder.addString("water_mark", "on");
        if (model.custommark) postEntityBuilder.addString("op_mark", "1");
        
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build()).build();
        String response = null;
        try {
            response = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, null, task, true,
                    (canHashwall() ? hashwallDetector : null));
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        } catch (HttpWrongResponseException he) {
            if (recaptcha2 != null) Recaptcha2solved.push(captchaId, recaptcha2);
            handleWrongResponse(url, he);
        }
        saveUsercodeCookie();
        JSONObject makabaResult = new JSONObject(response);
        try {
            String statusResult = makabaResult.getString("Status");
            if (statusResult.equals("OK")) {
                try {
                    if (model.threadNumber != null) {
                        UrlPageModel redirect = new UrlPageModel();
                        redirect.type = UrlPageModel.TYPE_THREADPAGE;
                        redirect.chanName = CHAN_NAME;
                        redirect.boardName = model.boardName;
                        redirect.threadNumber = model.threadNumber;
                        redirect.postNumber = Long.toString(makabaResult.getLong("Num"));
                        return buildUrl(redirect);
                    }
                } catch (Exception e) {
                    Logger.e(TAG, e);
                }
                return null;
            } else if (statusResult.equals("Redirect")) {
                UrlPageModel redirect = new UrlPageModel();
                redirect.type = UrlPageModel.TYPE_THREADPAGE;
                redirect.chanName = CHAN_NAME;
                redirect.boardName = model.boardName;
                redirect.threadNumber = Long.toString(makabaResult.getLong("Target"));
                return buildUrl(redirect);
            }
        } catch (Exception e) {}
        throw new Exception(makabaResult.getString("Reason"));
    }
    
    @Override
    public String reportPost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = domainUrl + "makaba/makaba.fcgi?json=1";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString("task", "report").
                addString("posts", "").
                addString("board", model.boardName).
                addString("thread", model.threadNumber).
                addString("comment", ">>" + model.postNumber + " " + model.reportReason);
        
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build()).setNoRedirect(true).build();
        String response = null;
        try {
            response = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, null, task, true,
                    (canHashwall() ? hashwallDetector : null));
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        } catch (HttpWrongResponseException he) {
            handleWrongResponse(url, he);
        }
        try {
            JSONObject json = new JSONObject(response);
            if (json.getString("message_title").equals("Ошибок нет")) return null;
            throw new Exception(json.getString("message_title") + " " + json.getString("message"));
        } catch (Exception e) {
            Logger.e(TAG, e);
            throw new Exception(response);
        }
    }

    @Override
    public String buildUrl(UrlPageModel model) throws IllegalArgumentException {
        if (!model.chanName.equals(CHAN_NAME)) throw new IllegalArgumentException("wrong chan");
        if (model.boardName != null && !model.boardName.matches("\\w*")) throw new IllegalArgumentException("wrong board name");
        StringBuilder url = new StringBuilder(this.domainUrl);
        switch (model.type) {
            case UrlPageModel.TYPE_INDEXPAGE:
                break;
            case UrlPageModel.TYPE_BOARDPAGE:
                url.append(model.boardName).append("/");
                if (model.boardPage == UrlPageModel.DEFAULT_FIRST_PAGE) model.boardPage = 0;
                if (model.boardPage != 0) url.append(model.boardPage).append(".html");
                break;
            case UrlPageModel.TYPE_CATALOGPAGE:
                url.append(model.boardName).append("/").append(CATALOG_TYPES[model.catalogType]).append(".html");
                break;
            case UrlPageModel.TYPE_SEARCHPAGE:
                if (model.searchRequest.startsWith(HASHTAG_PREFIX)) {
                    url.append(model.boardName).append("/catalog.html").append(model.searchRequest);
                } else {
                    url.append("makaba/makaba.fcgi?task=search&board=").append(model.boardName)
                            .append("&find=").append(Uri.encode(model.searchRequest));
                }
                break;
            case UrlPageModel.TYPE_THREADPAGE:
                url.append(model.boardName).append("/res/").append(model.threadNumber).append(".html");
                if (model.postNumber != null && model.postNumber.length() != 0) url.append("#").append(model.postNumber);
                break;
            case UrlPageModel.TYPE_OTHERPAGE:
                url.append(model.otherPath.startsWith("/") ? model.otherPath.substring(1) : model.otherPath);
        }
        return url.toString();
    }

    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        String path = UrlPathUtils.getUrlPath(url, MakabaModule.this.domain, DOMAINS_LIST);
        if (path == null) throw new IllegalArgumentException("wrong domain");
        path = path.toLowerCase(Locale.US);
        
        UrlPageModel model = new UrlPageModel();
        model.chanName = CHAN_NAME;
        try {
            if (path.contains("/catalog")) {
                Matcher matcher = Pattern.compile("(.+?)/(catalog(?:_.+?)?)\\.html(" + HASHTAG_PREFIX + "[a-z]+)?").matcher(path);
                if (!matcher.find()) throw new Exception();
                model.boardName = matcher.group(1);
                if (matcher.group(3) == null) {
                    model.type = UrlPageModel.TYPE_CATALOGPAGE;
                    int index = Arrays.asList(CATALOG_TYPES).indexOf(matcher.group(2));
                    model.catalogType = index == -1 ? 0 : index;
                } else {
                    model.type = UrlPageModel.TYPE_SEARCHPAGE;
                    model.searchRequest = matcher.group(3);
                }
            } else if (path.startsWith("makaba/makaba.fcgi?") && path.contains("task=search") && path.contains("board=")) {
                model.type = UrlPageModel.TYPE_SEARCHPAGE;
                String boardName = path.substring(path.indexOf("board=") + 6);
                if (boardName.contains("&")) boardName = boardName.substring(0, boardName.indexOf("&"));
                model.boardName = boardName;
                if (!path.contains("find=")) throw new IllegalStateException("cannot parse search request");
                String request = path.substring(path.indexOf("find=") + 5);
                if (request.contains("&")) request = request.substring(0, request.indexOf("&"));
                model.searchRequest = Uri.decode(request);
            } else if (path.contains("/res/")) {
                model.type = UrlPageModel.TYPE_THREADPAGE;
                Matcher matcher = Pattern.compile("(.+?)/res/([0-9]+?).html(.*)").matcher(path);
                if (!matcher.find()) throw new Exception();
                model.boardName = matcher.group(1);
                model.threadNumber = matcher.group(2);
                if (matcher.group(3).startsWith("#")) {
                    String post = matcher.group(3).substring(1);
                    if (!post.equals("")) model.postNumber = post;
                }
            } else {
                model.type = UrlPageModel.TYPE_BOARDPAGE;
                
                if (path.indexOf("/") == -1) {
                    if (path.equals("")) throw new Exception();
                    model.boardName = path;
                    model.boardPage = 0;
                } else {
                    model.boardName = path.substring(0, path.indexOf("/"));
                    
                    String page = path.substring(path.lastIndexOf("/") + 1);
                    if (!page.equals("")) {
                        String pageNum = page.substring(0, page.indexOf(".html"));
                        model.boardPage = pageNum.equals("index") ? 0 : Integer.parseInt(pageNum);
                    } else model.boardPage = 0;
                }
            }
        } catch (Exception e) {
            if (path == null || path.length() == 0 || path.equals("/")) {
                model.type = UrlPageModel.TYPE_INDEXPAGE;
            } else {
                model.type = UrlPageModel.TYPE_OTHERPAGE;
                model.otherPath = path;
            }
        }
        return model;
    }

    @Override
    protected JSONObject downloadJSONObject(String url, boolean checkIfModified, ProgressListener listener, CancellableTask task) throws Exception {
        if (!canHashwall()) return super.downloadJSONObject(url, checkIfModified, listener, task);
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModified).build();
        try {
            JSONObject object = HttpStreamer.getInstance().getJSONObjectFromUrl(url, rqModel, httpClient, listener, task, true, hashwallDetector);
            if (task != null && task.isCancelled()) throw new Exception("interrupted");
            if (listener != null) listener.setIndeterminate();
            return object;
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        } catch (HttpWrongResponseException e) {
            handleWrongResponse(url, e);
            return null;
        }
    }

    @Override
    protected JSONArray downloadJSONArray(String url, boolean checkIfModified, ProgressListener listener, CancellableTask task) throws Exception {
        if (!canHashwall()) return super.downloadJSONArray(url, checkIfModified, listener, task);
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModified).build();
        try {
            JSONArray array = HttpStreamer.getInstance().getJSONArrayFromUrl(url, rqModel, httpClient, listener, task, true, hashwallDetector);
            if (task != null && task.isCancelled()) throw new Exception("interrupted");
            if (listener != null) listener.setIndeterminate();
            return array;
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        } catch (HttpWrongResponseException e) {
            handleWrongResponse(url, e);
            return null;
        }
    }
}
