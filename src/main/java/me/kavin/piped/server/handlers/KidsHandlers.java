package me.kavin.piped.server.handlers;

import me.kavin.piped.utils.ExceptionHandler;
import me.kavin.piped.utils.obj.ContentItem;
import me.kavin.piped.utils.resp.InvalidRequestResponse;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static me.kavin.piped.consts.Constants.YOUTUBE_SERVICE;
import static me.kavin.piped.consts.Constants.mapper;
import static me.kavin.piped.utils.CollectionUtils.collectPreloadedTabs;
import static me.kavin.piped.utils.CollectionUtils.collectRelatedItems;

public class KidsHandlers {

    // Curated list of popular kid-friendly YouTube channels
    private static final String[] KIDS_CHANNELS = {
            "UCbCmjCuTUZos6Inko4u57UQ", // Cocomelon - Nursery Rhymes
            "UCHnyfMqiRRG1u-2MsSQLbXA", // Veritasium (Educational)
            "UC6nSFpj9HTCZ5t-N3Rm3-HA", // Vsauce (Educational)
            "UCsooa4yRKGN_zEE8iknghZA", // TED-Ed (Educational)
            "UCX6OQ3DkcsbYNE6H8uQQuVA", // MrBeast (Family Friendly)
            "UChDKyKQ59fYz3JO2fl0Z6sg", // Blippi
            "UCpV1EyGuFFDP-T5ENE3W8Fw", // ChuChu TV Nursery Rhymes
            "UCJplp5SjeGSdVdwsfb9Q7lQ", // Sesame Street
            "UCl4-WBRqWA2MlxqYHb63j-w", // Super Simple Songs
            "UCgwyp8DPuVMzWWWQwJJOgOQ", // Peppa Pig Official Channel
            "UCKAqou7V9FAWXpZd9xtOg3Q", // Paw Patrol
            "UCelMeixAOTs2OQAAi9wU8-g", // SciShow Kids
            "UCH4BNI0-FOK2dMXoFtViWHw", // PBS Kids
            "UCzU8AS_KK4kYg9Y1N8IVYrA", // Ryan's World
            "UC-ViW8jJrfHOPft62xH60nw", // Kids Diana Show
    };

    /**
     * Fetches kid-friendly videos from curated YouTube channels.
     * This provides an alternative to YouTube's trending that focuses on
     * family-friendly content.
     *
     * @param region The region code (used for compatibility, but not heavily relied
     *               upon)
     * @return JSON bytes containing a list of kid-friendly videos
     * @throws ExtractionException If there's an error extracting video information
     * @throws IOException         If there's a network error
     */
    public static byte[] kidsVideosResponse(String region)
            throws ExtractionException, IOException {

        if (region == null)
            ExceptionHandler.throwErrorResponse(new InvalidRequestResponse("region is a required parameter"));

        final List<ContentItem> kidsVideos = new ArrayList<>();

        // Fetch videos from multiple kid-friendly channels
        int channelsToFetch = Math.min(5, KIDS_CHANNELS.length); // Fetch from 5 channels to get variety

        for (int i = 0; i < channelsToFetch; i++) {
            try {
                String channelUrl = "https://www.youtube.com/channel/" + KIDS_CHANNELS[i];

                // Get channel info
                ChannelInfo channelInfo = ChannelInfo.getInfo(channelUrl);

                // Find the videos tab
                var videosTab = collectPreloadedTabs(channelInfo.getTabs())
                        .stream()
                        .filter(tab -> tab.getContentFilters().contains(ChannelTabs.VIDEOS))
                        .findFirst();

                if (videosTab.isPresent()) {
                    // Get videos from the channel
                    ChannelTabInfo tabInfo = ChannelTabInfo.getInfo(YOUTUBE_SERVICE, videosTab.get());

                    if (tabInfo.getRelatedItems() != null && !tabInfo.getRelatedItems().isEmpty()) {
                        List<ContentItem> channelVideos = collectRelatedItems(tabInfo.getRelatedItems());

                        // Add up to 4 videos from each channel
                        int videosToAdd = Math.min(4, channelVideos.size());
                        kidsVideos.addAll(channelVideos.subList(0, videosToAdd));
                    }
                }

                // Stop if we have enough videos (aim for ~20 videos total)
                if (kidsVideos.size() >= 20) {
                    break;
                }

            } catch (Exception e) {
                // If one channel fails, continue with others
                System.err.println("Failed to fetch from channel " + KIDS_CHANNELS[i] + ": " + e.getMessage());
            }
        }

        // If we didn't get enough videos, try a few more channels
        if (kidsVideos.size() < 15 && channelsToFetch < KIDS_CHANNELS.length) {
            for (int i = channelsToFetch; i < KIDS_CHANNELS.length && kidsVideos.size() < 20; i++) {
                try {
                    String channelUrl = "https://www.youtube.com/channel/" + KIDS_CHANNELS[i];
                    ChannelInfo channelInfo = ChannelInfo.getInfo(channelUrl);

                    var videosTab = collectPreloadedTabs(channelInfo.getTabs())
                            .stream()
                            .filter(tab -> tab.getContentFilters().contains(ChannelTabs.VIDEOS))
                            .findFirst();

                    if (videosTab.isPresent()) {
                        ChannelTabInfo tabInfo = ChannelTabInfo.getInfo(YOUTUBE_SERVICE, videosTab.get());

                        if (tabInfo.getRelatedItems() != null && !tabInfo.getRelatedItems().isEmpty()) {
                            List<ContentItem> channelVideos = collectRelatedItems(tabInfo.getRelatedItems());
                            int videosToAdd = Math.min(3, channelVideos.size());
                            kidsVideos.addAll(channelVideos.subList(0, videosToAdd));
                        }
                    }
                } catch (Exception e) {
                    // Silently continue
                }
            }
        }

        return mapper.writeValueAsBytes(kidsVideos);
    }
}
