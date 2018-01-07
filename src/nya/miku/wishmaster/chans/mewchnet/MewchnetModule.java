/*
 * Overchan Android (Meta Imageboard Client)
 * Copyright (C) 2014-2016  miku-nyan <https://github.com/miku-nyan>
 * Copyright (C) 2016-2018 Overchan Android community.
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

package nya.miku.wishmaster.chans.mewchnet;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.v4.content.res.ResourcesCompat;

import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.AbstractLynxChanModule;

public class MewchnetModule extends AbstractLynxChanModule {
    private static final String CHAN_NAME = "mewch.net";
    private static final String DISPLAYING_NAME = "Mewch";
    private static final String DOMAIN = "mewch.net";

    public MewchnetModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }

    @Override
    protected String getUsingDomain() {
        return DOMAIN;
    }

    @Override
    protected boolean canHttps() {
        return true;
    }
    
    @Override
    public String getChanName() {
        return CHAN_NAME;
    }

    @Override
    public String getDisplayingName() {
        return DISPLAYING_NAME;
    }

    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_mewch, null);
    }
}
