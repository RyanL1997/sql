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
  private static final String ACCESS_KEY = "";
  private static final String SECRET_KEY = "";
  private static final String SESSION_TOKEN = "";

  private static final String REGION = "us-east-1";
  private static final String SERVICE = "aoss";
  private static final String AOSS_ENDPOINT = "";

  // AOSS-specific headers
  private static final String AOSS_ACCOUNT_ID = "";
  private static final String AOSS_COLLECTION_ID = ""; // Extracted from endpoint

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
      // Create AWS request for signing without touching the original request entity
      Request<Void> awsRequest = createSigningRequest(request);

      // Sign the request
      signer.sign(awsRequest, credentials);

      // Copy only the AWS authentication headers - never touch request body
      String authorization = awsRequest.getHeaders().get("Authorization");
      String amzDate = awsRequest.getHeaders().get("x-amz-date");
      String amzSecurityToken = awsRequest.getHeaders().get("x-amz-security-token");

      if (authorization != null) {
        request.setHeader("Authorization", authorization);
      }
      if (amzDate != null) {
        request.setHeader("x-amz-date", amzDate);
      }
      if (amzSecurityToken != null) {
        request.setHeader("x-amz-security-token", amzSecurityToken);
      }

      // Add AOSS-specific headers required for AOSS APIs
      request.setHeader("X-Amzn-Aoss-Account-Id", AOSS_ACCOUNT_ID);
      request.setHeader("X-Amzn-Aoss-Collection-Id", AOSS_COLLECTION_ID);

    } catch (Exception e) {
      throw new HttpException("Failed to sign AWS request", e);
    }
  }

  /**
   * Create AWS request for signing that never touches the original request entity. This completely
   * avoids HTTP encoding conflicts by using empty body SHA256.
   */
  private Request<Void> createSigningRequest(HttpRequest httpRequest) throws IOException {
    String requestPath = httpRequest.getRequestUri();
    URI fullEndpoint = URI.create("https://" + AOSS_ENDPOINT + ":443");

    Request<Void> awsRequest = new DefaultRequest<>(SERVICE);
    awsRequest.setHttpMethod(HttpMethodName.fromValue(httpRequest.getMethod()));
    awsRequest.setEndpoint(fullEndpoint);
    awsRequest.setResourcePath(requestPath);

    // Required headers for signing
    awsRequest.addHeader("host", fullEndpoint.getHost());

    // Always use empty body SHA256 to avoid any entity interference
    awsRequest.addHeader("x-amz-content-sha256", sha256Hex("".getBytes(StandardCharsets.UTF_8)));

    // Add timestamp
    String timestamp =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    awsRequest.addHeader("x-amz-date", timestamp);

    // Add session token
    awsRequest.addHeader("x-amz-security-token", SESSION_TOKEN);

    // Add AOSS-specific headers before signing so they're included in the signature
    awsRequest.addHeader("X-Amzn-Aoss-Account-Id", AOSS_ACCOUNT_ID);
    awsRequest.addHeader("X-Amzn-Aoss-Collection-Id", AOSS_COLLECTION_ID);

    return awsRequest;
  }

  private Request<Void> createMinimalAwsRequest(HttpRequest httpRequest) throws IOException {
    // Create a minimal AWS request for signing without copying problematic headers
    String requestPath = httpRequest.getRequestUri();
    URI fullEndpoint = URI.create("https://" + AOSS_ENDPOINT + ":443");

    Request<Void> awsRequest = new DefaultRequest<>(SERVICE);
    awsRequest.setHttpMethod(HttpMethodName.fromValue(httpRequest.getMethod()));
    awsRequest.setEndpoint(fullEndpoint);
    awsRequest.setResourcePath(requestPath);

    // Add only essential headers for signing - avoid content headers that cause conflicts
    awsRequest.addHeader("host", fullEndpoint.getHost());

    // Don't add x-amz-content-sha256 for GET requests - AOSS doesn't expect it
    // Only add it for POST/PUT requests that have actual content

    // Add timestamp
    String timestamp =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    awsRequest.addHeader("x-amz-date", timestamp);

    // Add session token
    awsRequest.addHeader("x-amz-security-token", SESSION_TOKEN);

    // Add AOSS-specific headers before signing so they're included in the signature
    awsRequest.addHeader("X-Amzn-Aoss-Account-Id", AOSS_ACCOUNT_ID);
    awsRequest.addHeader("X-Amzn-Aoss-Collection-Id", AOSS_COLLECTION_ID);

    // Debug logging to see what's in the AWS request before signing
    System.out.println("=== AWS REQUEST BEFORE SIGNING ===");
    System.out.println("AWS Request Path: " + awsRequest.getResourcePath());
    System.out.println("AWS Request Headers: " + awsRequest.getHeaders());
    System.out.println("==================================");

    return awsRequest;
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
    // Skip content-related headers that might interfere with encoding
    Arrays.stream(httpRequest.getHeaders())
        .filter(
            header ->
                !header.getName().equalsIgnoreCase("Content-Length")
                    && !header.getName().equalsIgnoreCase("Content-Type")
                    && !header.getName().equalsIgnoreCase("Content-Encoding"))
        .forEach(
            header -> {
              awsRequest.addHeader(header.getName(), header.getValue());
            });

    // Handle request body - for POST requests, we need to provide content hash
    // We ONLY set the content hash header, not the actual content to avoid encoding conflicts
    if ("POST".equals(httpRequest.getMethod()) || "PUT".equals(httpRequest.getMethod())) {
      // For now, use empty content hash to avoid reading entity which would interfere with encoding
      byte[] bodyBytes = getActualEntityContent(entity);

      // Only set the SHA256 hash header - do NOT set content or Content-Length on AWS request
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

  private byte[] getActualEntityContent(EntityDetails entity) throws IOException {
    // For AWS signing we need the actual content hash, but reading the entity here
    // would interfere with the request encoding. For now, use empty content
    // This is a limitation - ideally we'd compute hash from actual request body
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
