/**
 * The Lingara API client (ADR 29.9.26r D1).
 *
 * <p>{@code com.getlingara.client} is the client, its options and its errors; {@code
 * com.getlingara.client.model} is the models, generated from the API's specification; {@code
 * com.getlingara.client.events} is the event union, the webhook verifier and the event helpers (ADR
 * 30.9.26aa); {@code com.getlingara.client.canvas} is the canvas group, {@code client.canvases()}
 * (ADR 16.10.26ak); {@code com.getlingara.client.dialogue} is the dialogue group, {@code
 * client.dialogues()} (ADR 29.10.26c).
 */
module com.getlingara.client {
  requires transitive java.net.http;
  requires transitive com.fasterxml.jackson.databind;

  exports com.getlingara.client;
  exports com.getlingara.client.model;
  exports com.getlingara.client.events;
  exports com.getlingara.client.canvas;
  exports com.getlingara.client.dialogue;

  opens com.getlingara.client.model to
      com.fasterxml.jackson.databind;
}
