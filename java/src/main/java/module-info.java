/**
 * The Lingara API client (ADR 29.9.26r D1).
 *
 * <p>{@code com.getlingara.client} is the client, its options and its errors; {@code
 * com.getlingara.client.model} is the models, generated from the API's specification; {@code
 * com.getlingara.client.events} is the event union, the webhook verifier and the event helpers (ADR
 * 30.9.26aa).
 */
module com.getlingara.client {
  requires transitive java.net.http;
  requires transitive com.fasterxml.jackson.databind;

  exports com.getlingara.client;
  exports com.getlingara.client.model;
  exports com.getlingara.client.events;

  opens com.getlingara.client.model to
      com.fasterxml.jackson.databind;
}
