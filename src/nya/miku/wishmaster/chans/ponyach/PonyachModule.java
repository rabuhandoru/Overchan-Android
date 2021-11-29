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

package nya.miku.wishmaster.chans.ponyach;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.client.entity.UrlEncodedFormEntity;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;
import cz.msebera.android.httpclient.message.BasicNameValuePair;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.support.v4.content.res.ResourcesCompat;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;
import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.AbstractWakabaModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.AttachmentModel;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.api.util.ChanModels;
import nya.miku.wishmaster.api.util.RegexUtils;
import nya.miku.wishmaster.api.util.WakabaReader;
import nya.miku.wishmaster.common.Async;
import nya.miku.wishmaster.common.IOUtils;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.recaptcha.Recaptcha2;
import nya.miku.wishmaster.http.recaptcha.Recaptcha2solved;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpResponseModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;

@SuppressLint("SimpleDateFormat")
public class PonyachModule extends AbstractWakabaModule {
    private static final String CHAN_NAME = "ponyach";
    private static final String DEFAULT_DOMAIN = "ponyach.ru";
    private static final String[] DOMAINS = new String[] { DEFAULT_DOMAIN };
    
    private static final DateFormat DATE_FORMAT;
    static {
        DateFormatSymbols symbols = new DateFormatSymbols();
        symbols.setMonths(new String[] {
                "Янв", "Фев", "Мар", "Апр", "Май", "Июнь", "Июль", "Авг", "Снт", "Окт", "Ноя", "Дек" });
        DATE_FORMAT = new SimpleDateFormat("dd MMMM yyyy HH:mm:ss", symbols);
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT+3"));
    }
    
    private static final SimpleBoardModel[] BOARDS = new SimpleBoardModel[] {
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "b", "/b/ - was never good", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "d", "Жалобы и предложения", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "test", "Полигон", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "r34", "r34", "", true)
        };
    
    private static final String RECAPTCHA_KEY = "6Lfp8AYUAAAAABmsvywGiiNyAIkpymMeZPvLUj30";
    
    private static final String PREF_KEY_DOMAIN = "PREF_KEY_DOMAIN";
    private static final String PREF_KEY_PHPSESSION_COOKIE = "PREF_KEY_PHPSESSION_COOKIE";
    private static final String PHPSESSION_COOKIE_NAME = "PHPSESSID";
    
    private static final Pattern ERROR_PATTERN = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);
    
    public PonyachModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }
    
    @Override
    public String getChanName() {
        return CHAN_NAME;
    }
    
    @Override
    public String getDisplayingName() {
        return "Поня.ч";
    }
    
    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_ponyach, null);
    }
    
    @Override
    protected boolean canCloudflare() {
        return true;
    }
    
    @Override
    protected boolean canHttps() {
        return true;
    }
    
    @Override
    protected String getUsingDomain() {
        String domain = preferences.getString(getSharedKey(PREF_KEY_DOMAIN), DEFAULT_DOMAIN);
        return TextUtils.isEmpty(domain) ? DEFAULT_DOMAIN : domain;
    }
    
    @Override
    protected String[] getAllDomains() {
        String domain = getUsingDomain();
        for (String d : DOMAINS) {
            if (domain.equals(d)) return DOMAINS;
        }
        String[] domains = new String[DOMAINS.length + 1];
        System.arraycopy(DOMAINS, 0, domains, 0, DOMAINS.length);
        domains[DOMAINS.length] = domain;
        return domains;
    }
    
    @Override
    protected void initHttpClient() {
        super.initHttpClient();
        loadPhpCookies();
    }
    
    private void loadPhpCookies() {
        loadPhpCookies(getUsingDomain());
    }

    private void loadPhpCookies(String usingDomain) {
        String phpSessionCookie = preferences.getString(getSharedKey(PREF_KEY_PHPSESSION_COOKIE), null);
        if (phpSessionCookie != null) {
            BasicClientCookie c = new BasicClientCookie(PHPSESSION_COOKIE_NAME, phpSessionCookie);
            c.setDomain(usingDomain);
            httpClient.getCookieStore().addCookie(c);
        }
    }
    
    private void savePhpCookies() {
        for (Cookie cookie : httpClient.getCookieStore().getCookies()) {
            if (cookie.getName().equalsIgnoreCase(PHPSESSION_COOKIE_NAME) && cookie.getDomain().contains(getUsingDomain())) {
                preferences.edit().putString(getSharedKey(PREF_KEY_PHPSESSION_COOKIE), cookie.getValue()).commit();
            }
        }
    }
    
    @Override
    public void clearCookies() {
        super.clearCookies();
        preferences.edit().remove(getSharedKey(PREF_KEY_PHPSESSION_COOKIE)).commit();
    }

    @Override
    public void addPreferencesOnScreen(PreferenceGroup preferenceGroup) {
        final Context context = preferenceGroup.getContext();
        EditTextPreference passcodePref = new EditTextPreference(context);
        passcodePref.setTitle(R.string.ponyach_prefs_passcode);
        passcodePref.setDialogTitle(R.string.ponyach_prefs_passcode);
        passcodePref.getEditText().setFilters(new InputFilter[] { new InputFilter.LengthFilter(6) });
        passcodePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String newPasscode = (String) newValue;
                final CancellableTask passAuthTask = new CancellableTask.BaseCancellableTask();
                final ProgressDialog passAuthProgressDialog = new ProgressDialog(context);
                passAuthProgressDialog.setMessage("Logging in");
                passAuthProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        passAuthTask.cancel();
                    }
                });
                passAuthProgressDialog.setCanceledOnTouchOutside(false);
                passAuthProgressDialog.show();
                Async.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (passAuthTask.isCancelled()) return;
                            String url = getUsingUrl() + "passcode.php";
                            List<BasicNameValuePair> pairs = Collections.singletonList(new BasicNameValuePair("passcode_just_set", newPasscode));
                            HttpRequestModel request = HttpRequestModel.builder().setPOST(new UrlEncodedFormEntity(pairs, "UTF-8")).build();
                            HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, null, passAuthTask, false);
                            savePhpCookies();
                        } catch (final Exception e) {
                            if (context instanceof Activity) {
                                ((Activity) context).runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        String message = e.getMessage() == null ? resources.getString(R.string.error_unknown) : e.getMessage();
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        } finally {
                            passAuthProgressDialog.dismiss();
                        }
                    }
                });
                return false;
            }
        });
        preferenceGroup.addPreference(passcodePref);
        
        EditTextPreference domainPref = new EditTextPreference(context);
        domainPref.setTitle(R.string.pref_domain);
        domainPref.setDialogTitle(R.string.pref_domain);
        domainPref.setKey(getSharedKey(PREF_KEY_DOMAIN));
        domainPref.getEditText().setHint(DEFAULT_DOMAIN);
        domainPref.getEditText().setSingleLine();
        domainPref.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        domainPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                loadPhpCookies((String) newValue);
                return true;
            }
        });
        preferenceGroup.addPreference(domainPref);
        
        addHttpsPreference(preferenceGroup, useHttpsDefaultValue());
        addProxyPreferences(preferenceGroup);
        addClearCookiesPreference(preferenceGroup);
    }
    
    @Override
    protected WakabaReader getWakabaReader(InputStream stream, UrlPageModel urlModel) {
        return new WakabaReader(stream, DATE_FORMAT, canCloudflare()) {
            private final Pattern jsLinkPattern = Pattern.compile("<a[^>]*href=\"javascript:.*?</a>");
            private final Pattern quotePattern = Pattern.compile("<div class=\"unkfunc[^\"]*\"[^>]*>(.*?)</div>");
            private final Pattern aHrefPattern = Pattern.compile("<a href=\"(.+?)\"(?:[^>]*download=\"(.+?)\")?");
            private final Pattern attachmentSizePattern = Pattern.compile("([\\d.]+)[KM]B");
            private final Pattern attachmentPxSizePattern = Pattern.compile("(\\d+)x(\\d+)");
            private final char[] dateFilter = "class=\"mobile_date dast-date\"".toCharArray();
            private final char[] attachmentFilter = "class=\"filesize fs_".toCharArray();
            private ArrayList<AttachmentModel> myAttachments = new ArrayList<>();
            private int curDatePos = 0;
            private int curAttachmentPos = 0;
            @Override
            protected void customFilters(int ch) throws IOException {
                if (ch == dateFilter[curDatePos]) {
                    ++curDatePos;
                    if (curDatePos == dateFilter.length) {
                        skipUntilSequence(">".toCharArray());
                        parsePonyachDate(readUntilSequence("<".toCharArray()).trim());
                        curDatePos = 0;
                    }
                } else {
                    if (curDatePos != 0) curDatePos = ch == dateFilter[0] ? 1 : 0;
                }
                
                if (ch == attachmentFilter[curAttachmentPos]) {
                    ++curAttachmentPos;
                    if (curAttachmentPos == attachmentFilter.length) {
                        skipUntilSequence(">".toCharArray());
                        myParseAttachment(readUntilSequence("</span>".toCharArray()));
                        curAttachmentPos = 0;
                    }
                } else {
                    if (curAttachmentPos != 0) curAttachmentPos = ch == attachmentFilter[0] ? 1 : 0;
                }
            }
            @Override
            protected void parseDate(String date) {} //Turn off false triggering
            
            private void parsePonyachDate(String date) {
                super.parseDate(date.substring(date.indexOf(' ') + 1));
            }
            
            private void myParseAttachment(String html) {
                Matcher aHrefMatcher = aHrefPattern.matcher(html);
                if (aHrefMatcher.find()) {
                    AttachmentModel attachment = new AttachmentModel();
                    attachment.path = aHrefMatcher.group(1);
                    attachment.originalName = aHrefMatcher.group(2);
                    String ext = attachment.path.substring(attachment.path.lastIndexOf('.') + 1);
                    switch (ext) {
                        case "jpg":
                        case "jpeg":
                        case "png":
                            attachment.type = AttachmentModel.TYPE_IMAGE_STATIC;
                            break;
                        case "gif":
                            attachment.type = AttachmentModel.TYPE_IMAGE_GIF;
                            break;
                        case "svg":
                        case "svgz":
                            attachment.type = AttachmentModel.TYPE_IMAGE_SVG;
                            break;
                        case "webm":
                        case "mp4":
                            attachment.type = AttachmentModel.TYPE_VIDEO;
                            break;
                        default:
                            attachment.type = AttachmentModel.TYPE_OTHER_FILE;
                    }
                    attachment.thumbnail = attachment.path.replaceAll("/src/(\\d+)/(?:.*?)\\.(.*?)$", "/thumb/$1s.$2");
                    if (attachment.thumbnail.equals(attachment.path)) {
                        attachment.thumbnail = null;
                    } else if (attachment.type == AttachmentModel.TYPE_VIDEO) {
                        attachment.thumbnail = attachment.thumbnail.substring(0, attachment.thumbnail.lastIndexOf('.')) + ".png";
                    }
                    Matcher sizeMatcher = attachmentSizePattern.matcher(html);
                    if (sizeMatcher.find()) {
                        try {
                            int mul = sizeMatcher.group(0).endsWith("MB") ? 1024 : 1;
                            attachment.size = Math.round(Float.parseFloat(sizeMatcher.group(1)) * mul);
                        } catch (Exception e) {
                            attachment.size = -1;
                        }
                        try {
                            Matcher pxSizeMatcher = attachmentPxSizePattern.matcher(html);
                            if (!pxSizeMatcher.find(sizeMatcher.end())) throw new Exception();
                            attachment.width = Integer.parseInt(pxSizeMatcher.group(1));
                            attachment.height = Integer.parseInt(pxSizeMatcher.group(2));
                        } catch (Exception e) {
                            attachment.width = -1;
                            attachment.height = -1;
                        }
                    } else {
                        attachment.size = -1;
                        attachment.width = -1;
                        attachment.height = -1;
                    }
                    
                    myAttachments.add(attachment);
                }
            }
            
            @Override
            protected void postprocessPost(PostModel post) {
                post.comment = RegexUtils.replaceAll(post.comment, jsLinkPattern, "");
                post.comment = RegexUtils.replaceAll(post.comment, quotePattern, "<span class=\"unkfunc\">$1</span><br/>");
                post.attachments = myAttachments.toArray(new AttachmentModel[0]);
                myAttachments.clear();
            }
            
            @Override
            protected void parseThumbnail(String imgTag) {
                super.parseThumbnail(imgTag);
                if (imgTag.contains("/css/icons/locked.png")) currentThread.isClosed = true;
                if (imgTag.contains("/css/icons/sticky.png")) currentThread.isSticky = true;
            }
        };
    }
    
    @Override
    protected SimpleBoardModel[] getBoardsList() {
        return BOARDS;
    }
    
    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        BoardModel board = super.getBoard(shortName, listener, task);
        board.timeZoneId = "GMT+3";
        board.defaultUserName = "Аноним";
        board.uniqueAttachmentNames = false;
        board.readonlyBoard = false;
        board.requiredFileForNewThread = true;
        board.allowDeletePosts = false;
        board.allowDeleteFiles = false;
        board.allowReport = BoardModel.REPORT_NOT_ALLOWED;
        board.allowNames = true;
        board.allowSubjects = true;
        board.allowSage = true;
        board.allowEmails = true;
        board.ignoreEmailIfSage = true;
        board.allowCustomMark = false;
        board.allowRandomHash = true;
        board.allowIcons = false;
        board.attachmentsMaxCount = 5;
        board.markType = BoardModel.MARK_BBCODE;
        return board;
    }
    
    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = getUsingUrl() + "board.php";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString("board", model.boardName).
                addString("replythread", model.threadNumber == null ? "0" : model.threadNumber).
                addString("name", model.name).
                addString("em", model.sage ? "sage" : model.email).
                addString("subject", model.subject).
                addString("message", model.comment);
        
        if (HttpStreamer.getInstance().getStringFromUrl(getUsingUrl() + "recaptchav2.php?c=isnd",
                HttpRequestModel.DEFAULT_GET, httpClient, null, task, false).equals("1")) {
            String response = Recaptcha2solved.pop(RECAPTCHA_KEY);
            if (response == null) {
                throw Recaptcha2.obtain(getUsingUrl(), RECAPTCHA_KEY, null, CHAN_NAME, false);
            }
            postEntityBuilder.addString("g-recaptcha-response", response);
        }
        
        int filesCount = model.attachments != null ? model.attachments.length : 0;
        for (int i=0; i<filesCount; ++i) postEntityBuilder.addFile("upload[]", model.attachments[i], model.randomHash);
        
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build()).setNoRedirect(true).build();
        HttpResponseModel response = null;
        try {
            response = HttpStreamer.getInstance().getFromUrl(url, request, httpClient, null, task);
            if (response.statusCode == 302) {
                return fixRelativeUrl(response.locationHeader);
            } else if (response.statusCode == 200) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                IOUtils.copyStream(response.stream, output);
                String htmlResponse = output.toString("UTF-8");
                Matcher errorMatcher = ERROR_PATTERN.matcher(htmlResponse);
                if (errorMatcher.find()) throw new Exception(errorMatcher.group(1).trim());
                if (htmlResponse.contains("<strong>Вы забанены</strong>")) throw new Exception("Вы забанены");
            }
            throw new Exception(response.statusCode + " - " + response.statusReason);
        } finally {
            if (response != null) response.release();
            savePhpCookies();
        }
    }
    
    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        return super.parseUrl(url.replaceAll("res/0[05]{2}(\\d+)\\.html", "res/$1.html"));
    }

    @Override
    public String fixRelativeUrl(String url) {
        if (url.startsWith("//")) return (useHttps() ? "https:" : "http:") + url;
        return super.fixRelativeUrl(url);
    }
    
}
