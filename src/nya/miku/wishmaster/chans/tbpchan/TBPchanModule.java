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

package nya.miku.wishmaster.chans.tbpchan;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.v4.content.res.ResourcesCompat;

import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.AbstractVichanModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.util.ChanModels;

public class TBPchanModule extends AbstractVichanModule {

    private static final String CHAN_NAME = "tbpchan.net";
    private static final SimpleBoardModel[] BOARDS = new SimpleBoardModel[]{
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "a", "Anime & Manga", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "adv", "Advice", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "ama", "Ask Me Anything", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "auto", "Automobiles", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "b", "Random", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "biz", "Business", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "ck", "Food & Cooking", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "co", "Comics & Cartoons", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "cos", "Cosplay", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "cute", "Cute", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "d", "Lewd Alternative", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "draw", "Art, Drawing, and Photography", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "fag", "Men", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "fit", "Health & Fitness", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "flash", "Flash", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "gd", "Graphic Design", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "h", "Hentai", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "hist", "History", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "int", "International", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "jv", "Japanese Video Games", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "k", "Weapons", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "leftpol", "Left Politics", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "liberty", "Liberty", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "lit", "Literature", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "m", "Music", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "mech", "Mecha", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "n", "News", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "nat", "Nature", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "ota", "Otaku Culture", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "philosophy", "Philosophy", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "poke", "Pokémon", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "pol", "Politics", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "r", "Request", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "rel", "Religion", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "r9k", "ROBOTΩ", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "s", "Women", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "sci", "Science", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "sport", "Sports", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "sudo", "TBPChan Meta", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "test", "Test Board", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "t", "Text Chat", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "tech", "Technology", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "tg", "Traditional Games", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "toy", "Toys", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "tv", "Television & Film", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "v", "Video Games", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "w", "Wallpapers General", "", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "wal", "Wallpapers", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "wx", "Animated", "", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "x", "Paranormal", "", false),
    };

    public TBPchanModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }

    @Override
    public String getChanName() {
        return CHAN_NAME;
    }

    @Override
    public String getDisplayingName() {
        return "TBPchan.net";
    }

    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_tbp, null);
    }

    @Override
    protected String getUsingDomain() {
        return "tbpchan.net";
    }

    @Override
    protected boolean canHttps() {
        return true;
    }

    @Override
    protected SimpleBoardModel[] getBoardsList() {
        return BOARDS;
    }

    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        BoardModel model = super.getBoard(shortName, listener, task);
        model.attachmentsMaxCount = 4;
        model.allowCustomMark = true;
        model.customMarkDescription = "Spoiler";
        return model;
    }

    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        super.sendPost(model, listener, task);
        return null;
    }

    @Override
    public String fixRelativeUrl(String url) {
        if (url.startsWith("?/")) url = url.substring(1);
        return super.fixRelativeUrl(url);
    }
}
