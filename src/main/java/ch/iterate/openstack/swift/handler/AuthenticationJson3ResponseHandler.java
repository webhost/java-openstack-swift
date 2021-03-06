package ch.iterate.openstack.swift.handler;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ResponseHandler;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.HTTP;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ch.iterate.openstack.swift.AuthenticationResponse;
import ch.iterate.openstack.swift.Response;
import ch.iterate.openstack.swift.exception.AuthorizationException;
import ch.iterate.openstack.swift.exception.GenericException;
import ch.iterate.openstack.swift.model.Region;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

public class AuthenticationJson3ResponseHandler implements ResponseHandler<AuthenticationResponse> {

    public AuthenticationResponse handleResponse(final HttpResponse response) throws IOException {
        if(response.getStatusLine().getStatusCode() == HttpStatus.SC_CREATED) {
            Charset charset = HTTP.DEF_CONTENT_CHARSET;
            ContentType contentType = ContentType.get(response.getEntity());
            if(contentType != null) {
                if(contentType.getCharset() != null) {
                    charset = contentType.getCharset();
                }
            }
            try {
                final JsonParser parser = new JsonParser();
                final JsonObject json = parser.parse(new InputStreamReader(response.getEntity().getContent(), charset)).getAsJsonObject();
                final JsonObject auth = json.getAsJsonObject("token");
                final JsonObject user = auth.getAsJsonObject("user");
                final String token = user.get("id").getAsString();
                String defaultRegion = null;
                if(auth.get("region") != null) {
                    // The geographic location of the service endpoint
                    defaultRegion = auth.get("region").getAsString();
                }
                final Set<Region> regions = new HashSet<Region>();
                if(auth.get("catalog") != null) {
                    final JsonArray catalog = auth.getAsJsonArray("catalog");
                    final Map<String, String> cdnUrls = new HashMap<String, String>();
                    for(JsonElement e : catalog) {
                        final JsonObject serviceCatalog = e.getAsJsonObject();
                        if(serviceCatalog.get("type").getAsString().equals("rax:object-cdn")) {
                            for(JsonElement endpoint : serviceCatalog.getAsJsonArray("endpoints")) {
                                if("public".equals(endpoint.getAsJsonObject().get("interface").getAsString())) {
                                    String regionId = endpoint.getAsJsonObject().get("region").getAsString();
                                    String publicUrl = endpoint.getAsJsonObject().get("url").getAsString();
                                    cdnUrls.put(regionId, publicUrl);
                                }
                            }
                        }
                        if(serviceCatalog.get("type").getAsString().equals("hpext:cdn")) {
                            for(JsonElement endpoint : serviceCatalog.getAsJsonArray("endpoints")) {
                                if("public".equals(endpoint.getAsJsonObject().get("interface").getAsString())) {
                                    String regionId = endpoint.getAsJsonObject().get("region").getAsString();
                                    String publicUrl = endpoint.getAsJsonObject().get("url").getAsString();
                                    cdnUrls.put(regionId, publicUrl);
                                }
                            }
                        }
                    }
                    for(JsonElement e : catalog) {
                        final JsonObject serviceCatalog = e.getAsJsonObject();
                        if(serviceCatalog.get("type").getAsString().equals("object-store")) {
                            for(JsonElement endpoint : serviceCatalog.getAsJsonArray("endpoints")) {
                                if("public".equals(endpoint.getAsJsonObject().get("interface").getAsString())) {
                                    String regionId = endpoint.getAsJsonObject().get("region").getAsString();
                                    String publicUrl = endpoint.getAsJsonObject().get("url").getAsString();
                                    String cdnUrl = cdnUrls.containsKey(regionId) ? cdnUrls.get(regionId) : null;
                                    regions.add(new Region(regionId, URI.create(publicUrl), cdnUrl == null ? null : URI.create(cdnUrl),
                                            regionId.equals(defaultRegion)));
                                }
                            }
                        }
                    }
                }
                return new AuthenticationResponse(response, token, regions);
            }
            catch(JsonParseException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        else if(response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED
                || response.getStatusLine().getStatusCode() == HttpStatus.SC_FORBIDDEN) {
            throw new AuthorizationException(new Response(response));
        }
        throw new GenericException(new Response(response));
    }
}
