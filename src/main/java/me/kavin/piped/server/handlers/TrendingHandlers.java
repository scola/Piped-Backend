package me.kavin.piped.server.handlers;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;

import java.io.IOException;

public class TrendingHandlers {
    public static byte[] trendingResponse(String region)
            throws ExtractionException, IOException {

        // Use KidsHandlers to return kid-friendly content instead of regular trending
        return KidsHandlers.kidsVideosResponse(region);
    }
}
