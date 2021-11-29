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

package nya.miku.wishmaster.chans.newnullchan;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.preference.EditTextPreference;
import android.preference.PreferenceGroup;
import android.support.v4.content.res.ResourcesCompat;
import android.text.InputType;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpHeaders;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;
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
import nya.miku.wishmaster.api.util.UrlPathUtils;
import nya.miku.wishmaster.common.IOUtils;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.JSONEntry;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpResponseModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;
import nya.miku.wishmaster.http.streamer.HttpWrongStatusCodeException;
import nya.miku.wishmaster.lib.base64.Base64;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONException;
import nya.miku.wishmaster.lib.org_json.JSONObject;

public class NewNullchanModule extends CloudflareChanModule {

    static final String CHAN_NAME = "ochan.ru";
    private static final String DEFAULT_DOMAIN = "ochan.ru";
    private static final String[] DOMAINS = new String[] { DEFAULT_DOMAIN };

    private static final Pattern BOARD_PATTERN = Pattern.compile("(\\w+)");
    private static final Pattern THREADPAGE_PATTERN = Pattern.compile("(\\w+)/(\\d+)(?:#(\\d+))?");
    private static final String CAPTCHA_BASE64_PREFIX = "data:image/png;base64,";
    private static final String PREF_KEY_DOMAIN = "domain";
    
    protected String sessionId = null;
    protected HashMap<String, String> captchas = null;
    protected HashMap<String, String> imageTokens = null;
    protected HashMap<String, String> threadOppost = null;
    protected HashMap<String, String> boardCursors = null;
    protected Map<String, BoardModel> boardsMap = null;

    public NewNullchanModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
        if (captchas == null) captchas = new HashMap<>();
        if (imageTokens == null) imageTokens = new HashMap<>();
        if (threadOppost == null) threadOppost = new HashMap<>();
        if (boardCursors == null) boardCursors = new HashMap<>();
    }

    public void putCaptcha(String captchaID, String answer) {
        if (captchas == null) captchas = new HashMap<>();
        captchas.put(captchaID, answer);
    }

    protected void updateSession(ProgressListener listener, CancellableTask task) throws Exception {
        String url = getUsingUrl() + "api/session";
        if (sessionId == null) {
            HttpResponseModel response = null;
            HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(false).build();
            try {
                response = HttpStreamer.getInstance().getFromUrl(url, rqModel, httpClient, listener, task);
                if (response.statusCode == 200) {
                    for (Header header : response.headers) {
                        if (header.getName().equalsIgnoreCase("x-session")) {
                            sessionId = header.getValue();
                            break;
                        }
                    }
                } else {
                    byte[] html = null;
                    try {
                        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(1024);
                        IOUtils.copyStream(response.stream, byteStream);
                        html = byteStream.toByteArray();
                    } catch (Exception e) {
                    }
                    if (html != null) {
                        checkCloudflareError(new HttpWrongStatusCodeException(response.statusCode, response.statusReason, html), url);
                    }
                    throw new HttpWrongStatusCodeException(response.statusCode, response.statusCode + " - " + response.statusReason);
                }
            } finally {
                if (response != null) response.release();
            }
        }
    }

    @Override
    public String getChanName() {
        return CHAN_NAME;
    }

    @Override
    public String getDisplayingName() {
        return "Øчан (ochan.ru)";
    }

    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_0chan, null);
    }

    private void addDomainPreference(PreferenceGroup group) {
        Context context = group.getContext();
        EditTextPreference domainPref = new EditTextPreference(context);
        domainPref.setTitle(R.string.pref_domain);
        domainPref.setDialogTitle(R.string.pref_domain);
        domainPref.setKey(getSharedKey(PREF_KEY_DOMAIN));
        domainPref.getEditText().setHint(DEFAULT_DOMAIN);
        domainPref.getEditText().setSingleLine();
        domainPref.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        group.addPreference(domainPref);
    }

    @Override
    public void addPreferencesOnScreen(PreferenceGroup preferenceGroup) {
        addDomainPreference(preferenceGroup);
        addHttpsPreference(preferenceGroup, true);
        addProxyPreferences(preferenceGroup);
        addClearCookiesPreference(preferenceGroup);
    }

    protected boolean useHttps() {
        return useHttps(true);
    }

    protected String getUsingDomain() {
        String domain = preferences.getString(getSharedKey(PREF_KEY_DOMAIN), DEFAULT_DOMAIN);
        return TextUtils.isEmpty(domain) ? DEFAULT_DOMAIN : domain;
    }

    protected String[] getAllDomains() {
        String domain = getUsingDomain();
        for (String d : DOMAINS) if (domain.equals(d)) return DOMAINS;
        String[] domains = new String[DOMAINS.length + 1];
        System.arraycopy(DOMAINS, 0, domains, 0, DOMAINS.length);
        domains[DOMAINS.length] = domain;
        return domains;
    }

    protected String getUsingUrl() {
        return (useHttps() ? "https://" : "http://") + getUsingDomain() + "/";
    }

    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        updateSession(listener, task);
        List<SimpleBoardModel> list = new ArrayList<>();
        Map<String, BoardModel> newMap = new HashMap<>();

        String url = getUsingUrl() + "api/board/list";
        JSONObject boardsJson = downloadJSONObject(url, (oldBoardsList != null && boardsMap != null), listener, task);
        if (boardsJson == null) return oldBoardsList;
        JSONArray boards = boardsJson.getJSONArray("boards");

        for (int i = 0, len = boards.length(); i < len; ++i) {
            BoardModel model = NewNullchanJsonMapper.mapBoardModel(getChanName(), boards.getJSONObject(i));
            model.chan = getChanName();
            newMap.put(model.boardName, model);
            list.add(new SimpleBoardModel(model));
        }

        boardsMap = newMap;
        return list.toArray(new SimpleBoardModel[0]);
    }

    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        if (boardsMap == null) {
            try {
                getBoardsList(listener, task, null);
            } catch (Exception e) {
            }
        }
        if (boardsMap != null && boardsMap.containsKey(shortName)) return boardsMap.get(shortName);
        return NewNullchanJsonMapper.getDefaultBoardModel(getChanName(), shortName);
    }

    @Override
    public ThreadModel[] getThreadsList(String boardName, int page, ProgressListener listener, CancellableTask task, ThreadModel[] oldList) throws Exception {
        updateSession(listener, task);
        String cursor = boardCursors.get(boardName);
        String url = getUsingUrl() + "api/board?dir=" + boardName + (page > 1 ? "&page=" + Integer.toString(page) : "") + "&session=" + sessionId;
        if (cursor != null && page > 1) {
            url = url + "&cursor=" + cursor;
        }
        JSONObject response = downloadJSONObject(url, oldList != null, listener, task);
        if (response == null) return oldList; //if not modified
        JSONArray threads = response.getJSONArray("threads");
        try {
            cursor = response.getJSONObject("pagination").getString("cursor");
        } catch (Exception e) {
        }
        if (cursor != null) {
            boardCursors.put(boardName, cursor);
        }
        ThreadModel[] result = new ThreadModel[threads.length()];
        for (int i = 0, len = threads.length(); i < len; ++i) {
            JSONObject threadInfo = threads.getJSONObject(i).getJSONObject("thread");
            JSONObject opPost = threads.getJSONObject(i).getJSONObject("opPost");
            JSONArray lastPosts = threads.getJSONObject(i).getJSONArray("lastPosts");
            int skippedPosts = threads.getJSONObject(i).optInt("skippedPosts", 0);
            ThreadModel curThread = new ThreadModel();
            curThread.threadNumber = threadInfo.getString("id");
            curThread.postsCount = skippedPosts + 1 + lastPosts.length();
            curThread.attachmentsCount = -1;
            curThread.isSticky = threadInfo.optBoolean("isPinned", false);
            curThread.isClosed = threadInfo.optBoolean("isLocked", false);
            curThread.posts = new PostModel[lastPosts.length() + 1];
            curThread.posts[0] = NewNullchanJsonMapper.mapPostModel(opPost, useHttps(), boardName, this, null);
            threadOppost.put(curThread.threadNumber, curThread.posts[0].number);
            Map<String, List<String>> replyMap = NewNullchanJsonMapper.buildReplyMap(lastPosts, curThread.posts[0].number);
            for (int j = 0; j < lastPosts.length(); j++) {
                curThread.posts[j + 1] = NewNullchanJsonMapper.mapPostModel(lastPosts.getJSONObject(j), useHttps(), boardName, this, replyMap);
            }
            result[i] = curThread;
        }
        return result;
    }

    private void updateAttachmentLinks(PostModel[] mergedPosts, List<PostModel> newPosts) {
        int start = 0;
        for (PostModel post : newPosts) {
            for (int i = start; i < mergedPosts.length; i++) {
                if (mergedPosts[i].number.equals(post.number)) {
                    mergedPosts[i].attachments = post.attachments;
                    start = i + 1;
                    break;
                }
            }
        }
    }

    @Override
    public PostModel[] getPostsList(String boardName, String threadNumber, ProgressListener listener, CancellableTask task, PostModel[] oldList)
            throws Exception {
        updateSession(listener, task);
        String url = getUsingUrl() + "api/thread?thread=" + threadNumber + "&session=" + sessionId; //&after=
        JSONObject response = downloadJSONObject(url, oldList != null, listener, task);
        if (response == null) return oldList; //if not modified
        JSONArray posts = response.getJSONArray("posts");
        PostModel[] result = new PostModel[posts.length()];
        Map<String, List<String>> replyMap = NewNullchanJsonMapper.buildReplyMap(posts, posts.getJSONObject(0).optString("id"));
        for (int i = 0; i < posts.length(); i++) {
            result[i] = NewNullchanJsonMapper.mapPostModel(posts.getJSONObject(i), useHttps(), boardName, this, replyMap);
        }
        if (oldList != null) {
            List<PostModel> newPosts = Arrays.asList(result);
            result = ChanModels.mergePostsLists(Arrays.asList(oldList), Arrays.asList(result));
            updateAttachmentLinks(result, newPosts);
        }
        threadOppost.put(result[0].parentThread, result[0].number);
        return result;
    }

    @Override
    public ExtendedCaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        if (boardName != null) return null;
        String url = getUsingUrl() + "api/captcha?session=" + sessionId;
        JSONObject response = downloadJSONObject(url, false, listener, task);
        String captchaId = response.optString("captcha");
        String captchaImage = response.optString("image");
        if (captchaImage.startsWith(CAPTCHA_BASE64_PREFIX)) {
            byte[] bitmap = Base64.decode(captchaImage.substring(CAPTCHA_BASE64_PREFIX.length()), Base64.DEFAULT);
            ExtendedCaptchaModel captcha = new ExtendedCaptchaModel();
            captcha.type = CaptchaModel.TYPE_NORMAL;
            captcha.bitmap = BitmapFactory.decodeByteArray(bitmap, 0, bitmap.length);
            captcha.captchaID = captchaId;
            return captcha;
        }
        return null;
    }

    protected String uploadFile(File attachment, ProgressListener listener, CancellableTask task) throws Exception {
        updateSession(listener, task);
        if (imageTokens.containsKey(attachment.getPath()))
            return imageTokens.get(attachment.getPath());
        String url = getUsingUrl() + "api/attachment/upload?session=" + sessionId;
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task);
        postEntityBuilder.addFile("file", attachment);
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build()).build();
        String response;
        try {
            response = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, null, task, true);
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        }
        JSONObject result = new JSONObject(response);
        if (!result.optBoolean("ok", false)) {
            String errorMessage = result.optString("reason");
            if (errorMessage.length() > 0) {
                if (errorMessage.startsWith("error: ")) {
                    errorMessage = errorMessage.substring(7);
                }
                throw new Exception(errorMessage);
            }
            throw new Exception(result.toString());
        }
        String token = result.getJSONObject("attachment").getString("token");
        if (!token.equals("")) imageTokens.put(token, attachment.getPath());
        return token;
    }

    protected String validateCaptcha(String captchaID, ProgressListener listener, CancellableTask task) {
        if (captchaID == null) return null;
        String captchaAnswer = captchas.get(captchaID);
        if (captchaAnswer == null) return null;
        String url = getUsingUrl() + "api/captcha?captcha=" + captchaID + "&answer=" + Uri.encode(captchaAnswer) + "&session=" + sessionId;
        JSONObject response;
        try {
            response = downloadJSONObject(url, false, listener, task);
        } catch (Exception e) {
            return null;
        }
        if (response == null) return null;
        if (!response.optBoolean("ok", false)) return null;
        captchas.remove(captchaID);
        return captchaID;
    }

    protected JSONObject getPost(String postId, ProgressListener listener, CancellableTask task) {
        String url = getUsingUrl() + "api/post?post=" + postId + "&session=" + sessionId;
        JSONObject response;
        try {
            response = downloadJSONObject(url, false, listener, task);
        } catch (Exception e) {
            return null;
        }
        return response.optJSONObject("post");
    }

    protected String getOpPostID(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String parent = threadOppost.get(model.threadNumber);
        if (parent == null) {
            PostModel[] parentThread = getPostsList(model.boardName, model.threadNumber, listener, task, null);
            parent = parentThread[0].number;
            threadOppost.put(model.threadNumber, parent);
        }
        return parent;
    }

    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        updateSession(listener, task);

        String url = null;
        String parent = null;
        String comment = model.comment;

        UrlPageModel urlModel = new UrlPageModel();
        urlModel.chanName = getChanName();
        urlModel.boardName = model.boardName;
        if (model.threadNumber == null) {
            urlModel.type = UrlPageModel.TYPE_BOARDPAGE;
            urlModel.boardPage = UrlPageModel.DEFAULT_FIRST_PAGE;
        } else {
            urlModel.type = UrlPageModel.TYPE_THREADPAGE;
            urlModel.threadNumber = model.threadNumber;
        }
        String referer = buildUrl(urlModel);
        
        if (model.threadNumber != null) {
            Pattern referencePattern = Pattern.compile("^\\s*>>(\\d+)");
            Matcher matcher = referencePattern.matcher(comment);
            if (matcher.find()) {
                parent = matcher.group(1);
                JSONObject post = getPost(parent, listener, task);
                if (post != null && model.threadNumber.equals(post.optString("threadId"))) {
                    comment = matcher.replaceFirst("");
                } else {
                    parent = getOpPostID(model, listener, task);
                }
            } else {
                parent = getOpPostID(model, listener, task);
            }
            url = getUsingUrl() + "api/thread/reply?parent=" + parent + "&session=" + sessionId;
        } else {
            url = getUsingUrl() + "api/thread/create?board=" + model.boardName + "&session=" + sessionId;
        }
        JSONObject jsonPayload = new JSONObject();
        jsonPayload.put("board", JSONObject.NULL);
        jsonPayload.put("thread", JSONObject.NULL);
        jsonPayload.put("parent", JSONObject.NULL);
        jsonPayload.put("sage", model.sage);
        jsonPayload.put("message", comment);
        JSONArray images = new JSONArray();
        if (model.attachments != null && model.attachments.length > 0) {
            for (int i=0; i<model.attachments.length; ++i) {
                images.put(uploadFile(model.attachments[i], listener, task));
            }
        }
        jsonPayload.put("images", images);
        
        String captchaId = null;
        try {
            captchaId = captchas.keySet().iterator().next();
        } catch (NoSuchElementException e) {
        }
        captchaId = validateCaptcha(captchaId, listener, task);
        jsonPayload.put("captcha", captchaId != null ? captchaId : JSONObject.NULL);
        JSONEntry payload = new JSONEntry(jsonPayload);
        Header[] customHeaders = new Header[] { new BasicHeader(HttpHeaders.REFERER, referer) };
        HttpRequestModel request = HttpRequestModel.builder().setPOST(payload).setCustomHeaders(customHeaders).setNoRedirect(true).build();
        String response = null;
        JSONObject result = null;
        try {
            response = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, null, task, true);
        } catch (HttpWrongStatusCodeException e1) {
            try {
                result = new JSONObject(e1.getHtmlString());
            } catch (JSONException e2) {
            }
            if (result != null) {
                int errorCode = result.optInt("error", 0);
                if (errorCode == 403) {
                    String require = result.getJSONObject("details").optString("require");
                    if (require.equals("captcha")) {
                        throw new NewNullchanCaptchaException();
                    } else throw new Exception(result.optString("message"));
                }
            }
            checkCloudflareError(e1, url);
            throw e1;
        }
        result = new JSONObject(response);
        if (!result.optBoolean("ok", false)) {
            JSONArray errors = result.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                String errorMessage = errors.optString(0, "");
                if (errorMessage.length() > 0) throw new Exception(errorMessage);
            }
            throw new Exception(response);
        }
        JSONObject post = result.getJSONObject("post");
        urlModel = new UrlPageModel();
        urlModel.type = UrlPageModel.TYPE_THREADPAGE;
        urlModel.boardName = post.optString("boardDir", model.boardName);
        urlModel.chanName = getChanName();
        urlModel.threadNumber = post.optString("threadId", model.threadNumber);
        urlModel.postNumber = post.optString("id", null);
        return this.buildUrl(urlModel);
    }

    @Override
    public String reportPost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        updateSession(listener, task);
        String url = getUsingUrl() + "api/moderation/reportPost?post=" + model.postNumber + "&session=" + sessionId;
        JSONObject jsonPayload = new JSONObject();
        jsonPayload.put("reason", model.reportReason);
        JSONEntry payload = new JSONEntry(jsonPayload);
        
        HttpRequestModel request = HttpRequestModel.builder().setPOST(payload).setNoRedirect(true).build();
        String response = null;
        try {
            response = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, null, task, true);
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        }
        JSONObject result = new JSONObject(response);
        if (!result.optBoolean("ok", false)) {
            String errorMessage = result.optString("reason");
            if (errorMessage.length() > 0) {
                throw new Exception(errorMessage);
            }
            throw new Exception(response);
        }
        return null;
    }

    @Override
    public String buildUrl(UrlPageModel model) throws IllegalArgumentException {
        if (!model.chanName.equals(getChanName())) throw new IllegalArgumentException("wrong chan");
        StringBuilder url = new StringBuilder(getUsingUrl());
        switch (model.type) {
            case UrlPageModel.TYPE_INDEXPAGE:
                break;
            case UrlPageModel.TYPE_BOARDPAGE:
                url.append(model.boardName).append("/");
                break;
            case UrlPageModel.TYPE_THREADPAGE:
                url.append(model.boardName).append("/").append(model.threadNumber);
                if (model.postNumber != null && model.postNumber.length() != 0)
                    url.append("#").append(model.postNumber);
                break;
            case UrlPageModel.TYPE_OTHERPAGE:
                url.append(model.otherPath.startsWith("/") ? model.otherPath.substring(1) : model.otherPath);
                break;
            default:
                throw new IllegalArgumentException("wrong page type");
        }
        return url.toString();
    }

    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        String path = UrlPathUtils.getUrlPath(url, getAllDomains());
        if (path == null) throw new IllegalArgumentException("wrong domain");
        path = path.toLowerCase(Locale.US);

        UrlPageModel model = new UrlPageModel();
        model.chanName = getChanName();
        try {
            if (path.length() == 0 || path.equals("/") || path.equals("index.html")) {
                model.type = UrlPageModel.TYPE_INDEXPAGE;
            } else {
                Matcher matcher = THREADPAGE_PATTERN.matcher(path.split("\\?", 1)[0]);
                if (matcher.find()) {
                    model.type = UrlPageModel.TYPE_THREADPAGE;
                    model.boardName = matcher.group(1);
                    model.threadNumber = matcher.group(2);
                    model.postNumber = matcher.group(3);
                } else {
                    String[] pathList = path.split("\\?", 1);
                    int page = 1;
                    if (pathList.length > 1) {
                        String[] args = pathList[1].split("&");
                        for (String arg : args) {
                            if (arg.contains("page")) {
                                try {
                                    page = Integer.parseInt(arg.split("=")[1]);
                                } catch (NumberFormatException e) {
                                }
                                break;
                            }
                        }
                    }
                    matcher = BOARD_PATTERN.matcher(pathList[0]);
                    if (!matcher.find()) throw new Exception();
                    model.type = UrlPageModel.TYPE_BOARDPAGE;
                    model.boardName = matcher.group(1);
                    model.boardPage = page;
                }
            }
        } catch (Exception e) {
            model.type = UrlPageModel.TYPE_OTHERPAGE;
            model.otherPath = path;
        }
        return model;
    }

    static class ExtendedCaptchaModel extends CaptchaModel {
        public String captchaID = "";
    }

}
