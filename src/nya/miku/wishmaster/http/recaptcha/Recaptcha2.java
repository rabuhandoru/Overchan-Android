/*
 * Overchan Android (Meta Imageboard Client)
 * Copyright (C) 2014-2015  miku-nyan <https://github.com/miku-nyan>
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

package nya.miku.wishmaster.http.recaptcha;

import nya.miku.wishmaster.http.interactive.InteractiveException;

/**
 * Recaptcha 2 Exception factory
 * @author miku-nyan
 *
 */
public class Recaptcha2 {
    public static InteractiveException obtain(String publicKey, String chanName, boolean fallback) throws RecaptchaException {
        return fallback ? new Recaptcha2fallback(publicKey, chanName) : new Recaptcha2js(publicKey);
    }
}
