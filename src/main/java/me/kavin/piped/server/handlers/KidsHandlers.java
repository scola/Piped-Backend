package me.kavin.piped.server.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import me.kavin.piped.utils.ExceptionHandler;
import me.kavin.piped.utils.obj.ContentItem;
import me.kavin.piped.utils.obj.StreamItem;
import me.kavin.piped.utils.resp.InvalidRequestResponse;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static me.kavin.piped.consts.Constants.h2client;
import static me.kavin.piped.consts.Constants.mapper;

public class KidsHandlers {

    private static final String YOUTUBE_KIDS_BROWSE_URL = "https://www.youtubekids.com/youtubei/v1/browse?alt=json";
    private static final String CLIENT_VERSION = "2.20251027.00.00";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * Fetches kid-friendly videos from YouTube Kids home page.
     *
     * @param region The region code (used for compatibility)
     * @return JSON bytes containing a list of kid-friendly videos
     * @throws IOException If there's a network error
     */
    public static byte[] kidsVideosResponse(String region)
            throws IOException {

        if (region == null)
            ExceptionHandler.throwErrorResponse(new InvalidRequestResponse("region is a required parameter"));

        // Build the YouTube Kids API request payload for home page
        String requestBody = String.format("""
                {
                  "context": {
                    "client": {
                      "clientName": "WEB_KIDS",
                      "clientVersion": "%s"
                    }
                  },
                  "browseId": "FEkids_home"
                }
                """, CLIENT_VERSION);

        RequestBody body = RequestBody.create(requestBody, JSON);
        Request request = new Request.Builder()
                .url(YOUTUBE_KIDS_BROWSE_URL)
                .post(body)
                .header("Content-Type", "application/json")
                .header("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .header("x-youtube-client-name", "76")
                .header("x-youtube-client-version", CLIENT_VERSION)
                .header("origin", "https://www.youtubekids.com")
                .build();

        try (Response response = h2client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("YouTube Kids API returned error: " + response.code());
            }

            String responseBody = response.body().string();
            JsonNode rootNode = mapper.readTree(responseBody);

            List<ContentItem> items = parseKidsBrowseResults(rootNode);

            return mapper.writeValueAsBytes(items);
        }
    }

    /**
     * Parses YouTube Kids browse/home page results from the API response.
     */
    private static List<ContentItem> parseKidsBrowseResults(JsonNode rootNode) {
        List<ContentItem> items = new ArrayList<>();

        try {
            // Recursively find all compactVideoRenderer objects in the response
            findVideoRenderers(rootNode, items);

        } catch (Exception e) {
            System.err.println("Error parsing YouTube Kids browse results: " + e.getMessage());
            e.printStackTrace();
        }

        return items;
    }

    /**
     * Recursively searches for compactVideoRenderer objects in the JSON tree.
     */
    private static void findVideoRenderers(JsonNode node, List<ContentItem> items) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            // Check if this node is a compactVideoRenderer
            if (node.has("compactVideoRenderer")) {
                JsonNode videoRenderer = node.get("compactVideoRenderer");
                StreamItem item = parseCompactVideoRenderer(videoRenderer);
                if (item != null) {
                    items.add(item);
                }
            }

            // Recursively search all child nodes
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                findVideoRenderers(entry.getValue(), items);
            }
        } else if (node.isArray()) {
            // Recursively search array elements
            for (JsonNode arrayElement : node) {
                findVideoRenderers(arrayElement, items);
            }
        }
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
                    "",
                    duration,
                    views,
                    -1,
                    false,
                    false);
        } catch (Exception e) {
            System.err.println("Error parsing video: " + e.getMessage());
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
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            } else if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600 +
                        Integer.parseInt(parts[1]) * 60 +
                        Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException e) {
            // Ignore
        }
        return -1;
    }

    private static long parseViews(String viewsText) {
        if (viewsText.isEmpty())
            return -1;

        try {
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
            // Ignore
        }
        return -1;
    }
}
