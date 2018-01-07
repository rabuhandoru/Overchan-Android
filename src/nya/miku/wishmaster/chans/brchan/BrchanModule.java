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

package nya.miku.wishmaster.chans.brchan;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.v4.content.res.ResourcesCompat;

import java.util.regex.Pattern;

import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.AbstractVichanModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.util.ChanModels;
import nya.miku.wishmaster.api.util.RegexUtils;
import nya.miku.wishmaster.lib.org_json.JSONObject;

public class BrchanModule extends AbstractVichanModule {
    private static final String CHAN_NAME = "brchan.org";
    private static final String DEFAULT_DOMAIN = "brchan.org";
    private static final Pattern PROTECTED_URL_PATTERN = Pattern.compile("<a[^>]*href=\"https?://privatelink.de/\\?([^\"]*)\"[^>]*>");
    
    private static final SimpleBoardModel[] BOARDS = new SimpleBoardModel[] {
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "a", "Anime", "Misc", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "b", "Random", "Misc", true),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "mod", "Suporte", "Misc", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "rus", "Russian", "Misc", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "vg", "Video games (RUS)", "Hobbies", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "gd", "Gamedev (RUS)", "Hobbies", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "ca", "Crypto-Anarchism (RUS)", "Hobbies", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "art", "Art", "Hobbies", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "mlp", "My Little Pony", "Hobbies", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "fag", "Faggotry", "Hobbies", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "pol", "Politics", "Politics", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "polru", "Russian politics", "Politics", false),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "fap", "Fap", "18+", true),
        ChanModels.obtainSimpleBoardModel(CHAN_NAME, "bb", "Bag", "18+", true),
    };
    
    public BrchanModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }
    
    @Override
    public String getChanName() {
        return CHAN_NAME;
    }
    
    @Override
    public String getDisplayingName() {
        return "BRchan";
    }
    
    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_brchan, null);
    }

    @Override
    protected boolean canCloudflare() {
        return true;
    }
    
    @Override
    protected String getUsingDomain() {
        return DEFAULT_DOMAIN;
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
        model.timeZoneId = "Brazil/East";
        model.defaultUserName = "Anônimo";
        model.allowNames = false;
        model.allowSubjects = false;
        model.allowCustomMark = true;
        model.customMarkDescription = "Spoiler";
        model.markType = BoardModel.MARK_INFINITY;
        return model;
    }
    
    @Override
    protected PostModel mapPostModel(JSONObject object, String boardName) {
        PostModel model = super.mapPostModel(object, boardName);
        model.comment = RegexUtils.replaceAll(model.comment, PROTECTED_URL_PATTERN, "<a href=\"$1\">");
        return model;
    }
    
}
