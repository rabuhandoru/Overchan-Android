package nya.miku.wishmaster.http.stormwall;

import android.net.Uri;

import java.net.URI;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.HttpChanModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.common.MainApplication;
import nya.miku.wishmaster.http.interactive.MultipurposeException;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpResponseModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.client.HttpClient;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;

public class StormwallExceptionAntiDDOS extends MultipurposeException {
    private static final long serialVersionUID = 1L;

    private static final String SERVICE_NAME = "Stormwall";
    private static final String TAG = "StormwallException";

    private static final Pattern PATTERN_INPUT = Pattern.compile("= get_jhash\\(([0-9]+)\\)");
    private static final Pattern PATTERN_COOKIE_VALUE2 = Pattern.compile("_HASH__=([^;]+);");

    protected static final String STORMWALL_COOKIE_NAME1 = "_JHASH__";
    protected static final String STORMWALL_COOKIE_NAME2 = "_HASH__";

    private URI url;
    private String chanName;
    private int input;
    
    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    public static StormwallExceptionAntiDDOS newInstance(String url, String html, String chanName) {
        try {
            StormwallExceptionAntiDDOS res = new StormwallExceptionAntiDDOS();
            res.url = new URI(url);
            res.chanName = chanName;
            Matcher m = PATTERN_INPUT.matcher(html);
            if (m.find()) {
                res.input = Integer.parseInt(m.group(1));
            } else {
                return null;
            }
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(String s) {
        Matcher matcher = Pattern.compile("([!'()*])").matcher(Uri.encode(s));
        StringBuffer sb = new StringBuffer();
        while (matcher.find())
            matcher.appendReplacement(sb, "%" + Integer.toHexString((int) matcher.group(1).charAt(0)));
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void saveCookie(HttpChanModule chan, String name, String value) {
        BasicClientCookie c = new BasicClientCookie(name, value);
        c.setDomain(url.getHost());
        chan.saveCookie(c);
    }

    @Override
    public String handle(CancellableTask task) {
        try {
            HttpChanModule chan = (HttpChanModule) MainApplication.getInstance().getChanModule(chanName);
            saveCookie(chan, STORMWALL_COOKIE_NAME1, StormwallTokenGenerator.encrypt(input));
            saveCookie(chan, "_JUA__", encode(MainApplication.getInstance().settings.getUserAgentString()));
            LockSupport.parkNanos(1000000000);
            HttpClient http = chan.getHttpClient();
            HttpRequestModel request = HttpRequestModel.builder().setGET().setNoRedirect(true).build();
            HttpResponseModel response = HttpStreamer.getInstance().getFromUrl(url.toString(), request, http, null, null);
            for (Header h: response.headers) {
                if (h.getName().equalsIgnoreCase("Set-Cookie")) {
                    Matcher m = PATTERN_COOKIE_VALUE2.matcher(h.getValue());
                    if (m.find()) {
                        saveCookie(chan, STORMWALL_COOKIE_NAME2, m.group(1));
                        response.release();
                        return null;
                    }
                }
            }
            response.release();
            return MainApplication.getInstance().resources.getString(R.string.error_antiddos);
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
