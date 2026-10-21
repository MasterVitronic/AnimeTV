package com.amarullz.androidtv.animetvjmto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some HLS masters advertise closing subtitles as a bare WebVTT file:
 *
 * <pre>
 * #EXT-X-MEDIA:URI="s/es.vtt",TYPE=SUBTITLES,GROUP-ID="subs",...
 * #EXT-X-STREAM-INF:...,SUBTITLES="subs"
 * </pre>
 *
 * That is not a valid subtitle rendition (RFC 8216 expects a playlist of WebVTT segments). ExoPlayer
 * parses it as a media playlist and fails the whole playback with
 * "Input does not start with the #EXTM3U header". The app already side-loads the subtitle file from
 * the provider, so dropping these renditions loses nothing and unblocks the video.
 */
final class HlsManifestFixer {
    private static final Pattern URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern GROUP = Pattern.compile("GROUP-ID=\"([^\"]+)\"");

    private HlsManifestFixer() {}

    static String stripFileSubtitles(String manifest) {
        if (manifest == null || !manifest.startsWith("#EXTM3U")) {
            return manifest;
        }
        String[] lines = manifest.split("\\r?\\n", -1);
        Set<String> droppedGroups = new HashSet<>();
        List<String> kept = new ArrayList<>(lines.length);

        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=SUBTITLES")) {
                Matcher uri = URI.matcher(line);
                if (uri.find() && !uri.group(1).toLowerCase().contains(".m3u8")) {
                    Matcher group = GROUP.matcher(line);
                    if (group.find()) {
                        droppedGroups.add(group.group(1));
                    }
                    continue;
                }
            }
            kept.add(line);
        }
        if (droppedGroups.isEmpty()) {
            return manifest;
        }

        StringBuilder out = new StringBuilder(manifest.length());
        for (String line : kept) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                for (String group : droppedGroups) {
                    line = line.replace("SUBTITLES=\"" + group + "\",", "")
                               .replace(",SUBTITLES=\"" + group + "\"", "")
                               .replace("SUBTITLES=\"" + group + "\"", "");
                }
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
