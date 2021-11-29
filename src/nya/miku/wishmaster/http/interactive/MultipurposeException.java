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

package nya.miku.wishmaster.http.interactive;

import nya.miku.wishmaster.api.interfaces.CancellableTask;
import android.app.Activity;

/**
 * Базовый класс для универсальных (интерактивных и неинтерактивных) исключений
 */
public abstract class MultipurposeException extends InteractiveException {
    @Override
    public final void handle(Activity activity, CancellableTask task, final Callback callback) {
        final String error;
        if ((error = handle(task)) == null && !task.isCancelled()) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess();
                }
            });
        } else if (!task.isCancelled()) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    callback.onError(error);
                }
            });
        }
    }
    /**
     * Метод вызывается синхронно.
     * Возвращаемое значение - строка с описанием ошибки, либо null.
     */
    public abstract String handle(CancellableTask task);
}
