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

package nya.miku.wishmaster.chans.tirech;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nya.miku.wishmaster.api.util.WakabaReader;

public class TirechReader extends WakabaReader {
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("data-time=\"(\\d+)\"");
    private static final long TIMESTAMP_OFFSET = 60*60*1000L;
    private static final char[] NUM_FILTER = "<a id=\"".toCharArray();
    
    private int curNumPos = 0;
    
    public TirechReader(InputStream in, boolean canCloudflare) {
        super(in, null, canCloudflare);
    }
    
    @Override
    protected void customFilters(int ch) throws IOException {
        if (ch == NUM_FILTER[curNumPos]) {
            ++curNumPos;
            if (curNumPos == NUM_FILTER.length) {
                currentPost.number = readUntilSequence("\"".toCharArray());
                curNumPos = 0;
            }
        } else {
            if (curNumPos != 0) curNumPos = ch == NUM_FILTER[0] ? 1 : 0;
        }
    }
    
    @Override
    protected void parseDate(String date) {
        Matcher matcher = TIMESTAMP_PATTERN.matcher(date);
        if (matcher.find()) {
            currentPost.timestamp = Long.parseLong(matcher.group(1)) * 1000 + TIMESTAMP_OFFSET;
        }
    }
    
}
