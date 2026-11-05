package com.getlingara.client;

import com.getlingara.client.internal.Pipeline;
import com.getlingara.client.internal.Pipeline.Call;
import com.getlingara.client.model.DialoguePlanRequest;
import java.util.Map;

/**
 * The embed calls that {@link LingaraClient}'s file budget cannot hold (ADR 3.11.26n D6). In the
 * client's package because {@link ApiResponse}'s constructor is package-private.
 */
final class EmbedCalls {
  private EmbedCalls() {}

  /**
   * The plan of a Procedural dialogue, sent once with no K4 loop, as the turn is: each attempt
   * spends one {@code embed:play} unit, a keep and every refusal after admission included, so a
   * {@code 429} or {@code 503} is thrown at once as an {@link ApiException} with its {@code
   * Retry-After} (ADR 3.11.26n D3). The five-argument {@link Call} is a retried one, so the
   * seven-argument form is spelled out.
   *
   * @param pipeline the client's request pipeline
   * @param body the plan request, its {@code document} sent as given
   * @return a fragment or a keep, both results
   */
  static ApiResponse<DialoguePlanResponse> planDialogue(
      Pipeline pipeline, DialoguePlanRequest body) {
    Call call =
        new Call(
            "POST",
            "/v1/embed/dialogue/plan",
            pipeline.bytes(body),
            "application/json",
            true,
            Map.of(),
            true);
    Pipeline.Answer<DialoguePlanResponse> answer = pipeline.json(call, DialoguePlanResponse.class);
    return new ApiResponse<>(answer.body(), answer.served());
  }
}
