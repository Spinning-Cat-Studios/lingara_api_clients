/**
 * The Lingara API client (ADR 29.9.26r D1).
 *
 * <p>{@code com.getlingara.client} is the client, its options and its errors; {@code
 * com.getlingara.client.model} is the models, generated from the API's specification.
 */
module com.getlingara.client {
  requires transitive java.net.http;
  requires transitive com.fasterxml.jackson.databind;

  exports com.getlingara.client;
  exports com.getlingara.client.model;

  opens com.getlingara.client.model to
      com.fasterxml.jackson.databind;
}
