package org.elasticsearch.discovery.ecs;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class EcsMetadataUtils {
    public static class EcsMetadataException extends IOException {
        public EcsMetadataException() {
            super();
        }

        public EcsMetadataException(String message) {
            super(message);
        }
    }

    private static final String ECS_METADATA_SERVICE_URL = "http://100.100.100.200";
    private static final String ECS_METADATA_ROOT = "/latest/meta-data/";

    private static String readResult(String url, int retries) throws IOException {
        final URL endpoint = new URL(url);
        int attempts = 0;

        while (true) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.connect();

                final int statusCode = connection.getResponseCode();
                if (statusCode == HttpURLConnection.HTTP_OK) {
                    final InputStream in = connection.getInputStream();
                    return IOUtils.toString(in, StandardCharsets.UTF_8);
                } else if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw new EcsMetadataException("Invalid metadata url " + url);
                }
            } catch (final EcsMetadataException e) {
                throw e;
            } catch (final IOException e) {
                attempts++;
                if (attempts > retries) {
                    throw e;
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    public static String getMetadata(String component) throws IOException {
        return getMetadata(component, 0);
    }

    public static String getMetadata(String component, int retries) throws IOException {
        return readResult(ECS_METADATA_SERVICE_URL + ECS_METADATA_ROOT + component, retries);
    }

    public static String getZoneId() throws IOException {
        return getMetadata("zone-id");
    }

    public static String findInstanceRoleName() {
        try {
            final String result = getMetadata("ram/security-credentials/");
            final String[] lines = result.split("\n");
            if (lines.length == 0) {
                return null;
            }

            return lines[0];
        } catch (final IOException e) {
            return null;
        }
    }
}
