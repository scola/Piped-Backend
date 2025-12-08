package me.kavin.piped.server.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import me.kavin.piped.utils.ExceptionHandler;
import me.kavin.piped.utils.obj.ContentItem;
import me.kavin.piped.utils.obj.SearchResults;
import me.kavin.piped.utils.obj.StreamItem;
import me.kavin.piped.utils.resp.InvalidRequestResponse;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static me.kavin.piped.consts.Constants.h2client;
import static me.kavin.piped.consts.Constants.mapper;

public class KidsSearchHandlers {

    private static final String YOUTUBE_KIDS_API_URL = "https://www.youtubekids.com/youtubei/v1/search?alt=json";
    private static final String CLIENT_VERSION = "2.20251120.00.00";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * Searches YouTube Kids for kid-friendly content.
     *
     * @param query  The search query
     * @param filter Search filter (ignored for kids search, always returns videos)
     * @return JSON bytes containing kid-friendly search results
     * @throws IOException If there's a network error
     */
    public static byte[] kidsSearchResponse(String query, String filter)
            throws IOException {

        if (StringUtils.isEmpty(query))
            ExceptionHandler.throwErrorResponse(new InvalidRequestResponse("query is a required parameter"));

        if (query.length() > 100)
            ExceptionHandler.throwErrorResponse(new InvalidRequestResponse("query is too long"));

        // Build the YouTube Kids API request payload
        String requestBody = String.format("""
                {
                  "context": {
                    "client": {
                      "clientName": "WEB_KIDS",
                      "clientVersion": "%s",
                      "hl": "en",
                      "kidsAppInfo": {
                        "contentSettings": {
                          "corpusPreference": "KIDS_CORPUS_PREFERENCE_TWEEN",
                          "kidsNoSearchMode": "YT_KIDS_NO_SEARCH_MODE_OFF"
                        }
                      }
                    }
                  },
                  "query": "%s"
                }
                """, CLIENT_VERSION, query.replace("\"", "\\\""));

        RequestBody body = RequestBody.create(requestBody, JSON);
        Request request = new Request.Builder()
                .url(YOUTUBE_KIDS_API_URL)
                .post(body)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = h2client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("YouTube Kids API returned error: " + response.code());
            }

            String responseBody = response.body().string();
            JsonNode rootNode = mapper.readTree(responseBody);

            List<ContentItem> items = parseKidsSearchResults(rootNode);

            // YouTube Kids search doesn't provide pagination in the same way
            // For now, we'll return empty nextpage
            return mapper.writeValueAsBytes(new SearchResults(items, null, null, false));
        }
    }

    /**
     * Parses YouTube Kids search results from the API response.
     */
    private static List<ContentItem> parseKidsSearchResults(JsonNode rootNode) {
        List<ContentItem> items = new ArrayList<>();

        try {
            // Navigate to the search results - simpler path for YouTube Kids
            JsonNode contents = rootNode
                    .path("contents")
                    .path("sectionListRenderer")
                    .path("contents");

            if (contents.isArray()) {
                for (JsonNode section : contents) {
                    JsonNode itemSection = section.path("itemSectionRenderer");
                    if (!itemSection.isMissingNode()) {
                        JsonNode sectionContents = itemSection.path("contents");
                        if (sectionContents.isArray()) {
                            for (JsonNode item : sectionContents) {
                                // YouTube Kids uses compactVideoRenderer instead of videoRenderer
                                JsonNode compactVideoRenderer = item.path("compactVideoRenderer");
                                if (!compactVideoRenderer.isMissingNode()) {
                                    ContentItem streamItem = parseCompactVideoRenderer(compactVideoRenderer);
                                    if (streamItem != null) {
                                        items.add(streamItem);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing YouTube Kids search results: " + e.getMessage());
            e.printStackTrace();
        }

        return items;
    }

    /**
     * Parses a single video item from YouTube Kids API response
     * (compactVideoRenderer).
     */
    private static StreamItem parseCompactVideoRenderer(JsonNode videoRenderer) {
        try {
            String videoId = videoRenderer.path("videoId").asText();
            if (videoId.isEmpty())
                return null;

            String title = extractText(videoRenderer.path("title"));
            String thumbnail = extractThumbnail(videoRenderer.path("thumbnail"));

            // YouTube Kids uses longBylineText for channel name
            String uploaderName = extractText(videoRenderer.path("longBylineText"));
            String channelId = extractChannelId(videoRenderer.path("longBylineText"));
            String uploaderUrl = channelId != null ? "/channel/" + channelId : null;
            String uploaderAvatar = extractOwnerThumbnail(videoRenderer.path("channelThumbnail"));
            String uploadedDate = extractText(videoRenderer.path("publishedTimeText"));

            // YouTube Kids doesn't have description in compact view
            String shortDescription = "";

            long duration = parseDuration(extractText(videoRenderer.path("lengthText")));
            long views = parseViews(extractText(videoRenderer.path("viewCountText")));

            return new StreamItem(
                    "/watch?v=" + videoId,
                    title,
                    thumbnail,
                    uploaderName,
                    uploaderUrl,
                    uploaderAvatar,
                    uploadedDate,
                    shortDescription,
                    duration,
                    views,
                    -1, // uploaded timestamp
                    false, // uploaderVerified
                    false // isShort
            );
        } catch (Exception e) {
            System.err.println("Error parsing video: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static String extractText(JsonNode textNode) {
        if (textNode.has("runs") && textNode.get("runs").isArray() && textNode.get("runs").size() > 0) {
            return textNode.get("runs").get(0).path("text").asText("");
        } else if (textNode.has("simpleText")) {
            return textNode.path("simpleText").asText("");
        }
        return "";
    }

    private static String extractThumbnail(JsonNode thumbnailNode) {
        JsonNode thumbnails = thumbnailNode.path("thumbnails");
        if (thumbnails.isArray() && thumbnails.size() > 0) {
            // Get the last (highest quality) thumbnail
            return thumbnails.get(thumbnails.size() - 1).path("url").asText("");
        }
        return "";
    }

    private static String extractOwnerThumbnail(JsonNode channelThumbnailNode) {
        JsonNode thumbnails = channelThumbnailNode.path("thumbnails");
        if (thumbnails.isArray() && thumbnails.size() > 0) {
            return thumbnails.get(0).path("url").asText("");
        }
        return "";
    }

    private static String extractChannelId(JsonNode ownerTextNode) {
        if (ownerTextNode.has("runs") && ownerTextNode.get("runs").isArray() && ownerTextNode.get("runs").size() > 0) {
            JsonNode browseEndpoint = ownerTextNode.get("runs").get(0)
                    .path("navigationEndpoint")
                    .path("browseEndpoint");
            return browseEndpoint.path("browseId").asText(null);
        }
        return null;
    }

    private static long parseDuration(String durationText) {
        if (durationText.isEmpty())
            return -1;

        try {
            String[] parts = durationText.split(":");
            if (parts.length == 2) {
                // MM:SS format
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            } else if (parts.length == 3) {
                // HH:MM:SS format
                return Integer.parseInt(parts[0]) * 3600 +
                        Integer.parseInt(parts[1]) * 60 +
                        Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException e) {
            // Ignore parsing errors
        }
        return -1;
    }

    private static long parseViews(String viewsText) {
        if (viewsText.isEmpty())
            return -1;

        try {
            // Extract numbers from text like "1.2M views" or "5,234 views"
            String numbers = viewsText.replaceAll("[^0-9.]", "");
            if (viewsText.toLowerCase().contains("k")) {
                return (long) (Float.parseFloat(numbers) * 1000);
            } else if (viewsText.toLowerCase().contains("m")) {
                return (long) (Float.parseFloat(numbers) * 1000000);
            } else if (viewsText.toLowerCase().contains("b")) {
                return (long) (Float.parseFloat(numbers) * 1000000000);
            } else {
                return Long.parseLong(numbers.replace(".", ""));
            }
        } catch (NumberFormatException e) {
            // Ignore parsing errors
        }
        return -1;
    }
}
