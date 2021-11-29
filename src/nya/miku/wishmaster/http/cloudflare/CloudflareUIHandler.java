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

package nya.miku.wishmaster.http.cloudflare;

import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.HttpChanModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.common.Async;
import nya.miku.wishmaster.http.client.ExtendedHttpClient;
import nya.miku.wishmaster.http.hcaptcha.Hcaptcha;
import nya.miku.wishmaster.http.hcaptcha.HcaptchaSolved;
import nya.miku.wishmaster.http.interactive.InteractiveException;
import cz.msebera.android.httpclient.cookie.Cookie;

import android.app.Activity;

/**
 * UI обработчик Cloudflare-исключений (статический класс)
 * @author miku-nyan
 *
 */

/*package*/ class CloudflareUIHandler {
    private CloudflareUIHandler() {}
    
    /**
     * Обработать исключение-запрос проверки Cloudflare.
     * Вызывать из фонового потока
     * @param e исключение {@link CloudflareException}
     * @param chan модуль чана
     * @param activity активность, в которой будет создан диалог (в случае проверки с капчей),
     * или в контексте которой будет создан WebView для Anti DDOS проверки с javascript.
     * Используется как доступ к UI потоку ({@link Activity#runOnUiThread(Runnable)})
     * @param cfTask отменяемая задача
     * @param callback интерфейс {@link InteractiveException.Callback}
     */
    static void handleCloudflare(final CloudflareException e, final HttpChanModule chan, final Activity activity, final CancellableTask cfTask,
            final InteractiveException.Callback callback) {
        if (cfTask.isCancelled()) return;
        
        if (!e.isHcaptcha()) {  // обычная anti DDOS проверка
            if (!CloudflareChecker.getInstance().isAvaibleAntiDDOS()) {
                //если анти ддос проверка уже проводится другим потоком, тогда подождем завершения и объявим успех
                //в случае, если проверка была по тому же ChanModule, проверка уже будет пройдена
                //в противном случае на следующей попытке (закачки) cloudflare выкинет исключение снова
                //и мы сможем обработать исключение для этого чана на свободном CloudflareChecker
                while (!CloudflareChecker.getInstance().isAvaibleAntiDDOS()) Thread.yield();
                if (!cfTask.isCancelled()) activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess();
                    }
                });
                return;
            }
            
            Cookie cfCookie = CloudflareChecker.getInstance().checkAntiDDOS(e, chan.getHttpClient(), cfTask, activity);
            if (cfCookie != null) {
                chan.saveCookie(cfCookie);
                if (!cfTask.isCancelled()) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess();
                        }
                    });
                }
            } else if (!cfTask.isCancelled()) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError(activity.getString(R.string.error_antiddos));
                    }
                });
            }
        } else {  // проверка с капчей
            Hcaptcha.obtain(e.getCheckUrl(), e.getHcaptchaPublicKey()).
                    handle(activity, cfTask, new InteractiveException.Callback() {
                @Override
                public void onSuccess() {
                    Async.runAsync(new Runnable() {
                        @Override
                        public void run() {
                            Cookie cfCookie = CloudflareChecker.getInstance().checkCaptcha(e, (ExtendedHttpClient)chan.getHttpClient(),
                                    cfTask, e.getCheckCaptchaUrl(), HcaptchaSolved.pop(e.getHcaptchaPublicKey()));
                            if (cfCookie != null) {
                                chan.saveCookie(cfCookie);
                                if (!cfTask.isCancelled()) {
                                    activity.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            callback.onSuccess();
                                        }
                                    });
                                }
                            } else {
                                //печенька не получена (вероятно, ответ неверный, загружаем капчу еще раз)
                                handleCloudflare(e, chan, activity, cfTask, callback);
                            }
                        }
                    });
                }
                @Override
                public void onError(String message) {
                    if (!cfTask.isCancelled()) callback.onError(message);
                }
            });
        }
    }
}
