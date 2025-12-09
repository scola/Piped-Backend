package me.kavin.piped.server.handlers;

import java.io.IOException;

public class TrendingHandlers {
    public static byte[] trendingResponse(String region)
            throws IOException {

        // Use KidsHandlers to return kid-friendly content instead of regular trending
        return KidsHandlers.kidsVideosResponse(region);
    }
}
