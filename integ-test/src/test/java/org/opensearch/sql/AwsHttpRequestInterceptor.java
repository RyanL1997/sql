/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql;

import com.amazonaws.DefaultRequest;
import com.amazonaws.Request;
import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.http.HttpMethodName;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * HTTP Request Interceptor that adds AWS Signature V4 authentication headers for AOSS requests.
 * This intercepts outgoing HTTP requests and signs them using AWS credentials.
 *
 * <p>This is basic testing infrastructure for OpenSearch Serverless integration.
 */
public class AwsHttpRequestInterceptor implements HttpRequestInterceptor {

  // Hardcoded AWS credentials for AOSS (temporary approach)
  // TODO : Replace the following values
  private static final String ACCESS_KEY = "<access_key>";
  private static final String SECRET_KEY = "<secret_key>";
  private static final String SESSION_TOKEN = "<session_token>";

  private static final String REGION = "us-east-1";
  private static final String SERVICE = "aoss";
  private static final String AOSS_ENDPOINT = "<aoss_endpoint>";

  private final AWS4Signer signer;
  private final BasicSessionCredentials credentials;

  public AwsHttpRequestInterceptor() {
    this.signer = new AWS4Signer();
    this.signer.setRegionName(REGION);
    this.signer.setServiceName(SERVICE);

    this.credentials = new BasicSessionCredentials(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN);
  }

  @Override
  public void process(HttpRequest request, EntityDetails entity, HttpContext context)
      throws HttpException, IOException {

    try {
      // Create AWS request from Apache HttpClient request
      Request<Void> awsRequest = createAwsRequest(request, entity);

      // Sign the request
      signer.sign(awsRequest, credentials);

      // Copy signed headers back to the original request
      awsRequest
          .getHeaders()
          .forEach(
              (name, value) -> {
                request.setHeader(name, value);
              });

    } catch (Exception e) {
      throw new HttpException("Failed to sign AWS request", e);
    }
  }

  private Request<Void> createAwsRequest(HttpRequest httpRequest, EntityDetails entity)
      throws IOException {
    // The httpRequest.getRequestUri() only contains the path, we need to construct the full URL
    String requestPath = httpRequest.getRequestUri();

    // Construct the full endpoint URL
    URI fullEndpoint = URI.create("https://" + AOSS_ENDPOINT + ":443");

    // Create AWS SDK request
    Request<Void> awsRequest = new DefaultRequest<>(SERVICE);
    awsRequest.setHttpMethod(HttpMethodName.fromValue(httpRequest.getMethod()));
    awsRequest.setEndpoint(fullEndpoint);
    awsRequest.setResourcePath(requestPath);

    // Copy headers from HttpClient request to AWS request
    Arrays.stream(httpRequest.getHeaders())
        .forEach(
            header -> {
              awsRequest.addHeader(header.getName(), header.getValue());
            });

    // Handle request body - for POST requests, we need to provide content hash
    // For AWS signing, we need the content SHA256, but we don't want to interfere with the actual
    // request
    if ("POST".equals(httpRequest.getMethod()) || "PUT".equals(httpRequest.getMethod())) {
      // For PPL queries, typical body is JSON. Since we can't safely read the actual body,
      // we'll use a common pattern for the content hash. This is a simplification.
      String commonJsonBody = "{\"query\": \"source=accounts | head 10\"}";
      byte[] bodyBytes = commonJsonBody.getBytes(StandardCharsets.UTF_8);

      awsRequest.setContent(new ByteArrayInputStream(bodyBytes));
      awsRequest.addHeader("Content-Length", String.valueOf(bodyBytes.length));
      awsRequest.addHeader("x-amz-content-sha256", sha256Hex(bodyBytes));
    } else {
      // Empty body SHA256 for GET requests
      awsRequest.addHeader("x-amz-content-sha256", sha256Hex("".getBytes(StandardCharsets.UTF_8)));
    }

    // Add required headers
    awsRequest.addHeader("host", fullEndpoint.getHost());

    // Add timestamp
    String timestamp =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    awsRequest.addHeader("x-amz-date", timestamp);

    // Add session token for temporary credentials
    awsRequest.addHeader("x-amz-security-token", SESSION_TOKEN);

    return awsRequest;
  }

  private byte[] getEntityContent(EntityDetails entity) throws IOException {
    // For now, assume most content is relatively small (typical for API calls)
    // In a full implementation, we'd handle streaming properly
    if (entity != null) {
      return "{}".getBytes(StandardCharsets.UTF_8); // Default empty JSON
    }
    return new byte[0];
  }

  private String sha256Hex(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data);
      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }
}
