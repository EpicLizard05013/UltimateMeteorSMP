package com.meteorsmp.core.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorsmp.core.PluginMain;
import org.bukkit.Bukkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class UpdateChecker {

    private final PluginMain plugin;
    private final String repoOwnerAndName;

    /**
     * @param plugin Instance of your main plugin
     * @param repoOwnerAndName GitHub repository in "Owner/Repo" format (e.g. "EpicLizard05013/UltimateMeteorSMP")
     */
    public UpdateChecker(PluginMain plugin, String repoOwnerAndName) {
        this.plugin = plugin;
        this.repoOwnerAndName = repoOwnerAndName;
    }

    public void checkAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.github.com/repos/" + repoOwnerAndName + "/releases"))
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", repoOwnerAndName + "-UpdateChecker")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("Could not check for updates (GitHub HTTP " + response.statusCode() + ").");
                    return;
                }

                JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
                if (releases.isEmpty()) {
                    return;
                }

                String currentVersion = plugin.getPluginMeta().getVersion().replace("v", "").trim();
                int updatesBehind = 0;
                String latestUrl = "";
                String latestVersion = "";
                boolean foundCurrent = false;

                for (int i = 0; i < releases.size(); i++) {
                    JsonObject release = releases.get(i).getAsJsonObject();

                    // Skip draft or pre-release builds
                    if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) {
                        continue;
                    }

                    String tagName = release.get("tag_name").getAsString().replace("v", "").trim();

                    if (latestVersion.isEmpty()) {
                        latestVersion = tagName;
                        latestUrl = release.get("html_url").getAsString();
                    }

                    if (tagName.equalsIgnoreCase(currentVersion)) {
                        foundCurrent = true;
                        break;
                    }

                    updatesBehind++;
                }

                if (!foundCurrent && updatesBehind > 0) {
                    // Current version isn't in the releases list (or is older than all fetched releases)
                    plugin.getLogger().warning(String.format(
                            "You are running an unreleased or outdated version (v%s). Latest release is v%s! Get it at %s",
                            currentVersion, latestVersion, latestUrl
                    ));
                } else if (updatesBehind > 0) {
                    String plural = updatesBehind == 1 ? "" : "s";
                    plugin.getLogger().warning(String.format(
                            "You are %d update%s behind the newest! Get it at %s",
                            updatesBehind, plural, latestUrl
                    ));
                } else {
                    plugin.getLogger().info("You are running the latest version (v" + currentVersion + ").");
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to perform GitHub update check: " + e.getMessage());
            }
        });
    }
}
