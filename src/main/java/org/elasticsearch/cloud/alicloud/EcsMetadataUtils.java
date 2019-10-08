package org.elasticsearch.cloud.alicloud;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class EcsMetadataUtils {
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
        int attempts = 0;
        InputStream in = null;
        URL endpoint = new URL(url);

        while (true) {
            try {
                HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.connect();

                int statusCode = connection.getResponseCode();
                if (statusCode == HttpURLConnection.HTTP_OK) {
                    in = connection.getInputStream();
                    return IOUtils.toString(in, StandardCharsets.UTF_8);
                } else if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw new EcsMetadataException("Invalid metadata url " + url);
                }
            } catch (EcsMetadataException e) {
                throw e;
            } catch (IOException e) {
                attempts++;
                if (attempts > retries) {
                    throw e;
                }
            } finally {
                org.apache.lucene.util.IOUtils.closeWhileHandlingException(in);
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

    public static String findRamProfile() {
        try {
            String result = getMetadata("ram/security-credentials/");
            String[] lines = result.split("\n");
            if (lines.length == 0) {
                return null;
            }

            return lines[0];
        } catch (IOException e) {
            return null;
        }
    }
}
